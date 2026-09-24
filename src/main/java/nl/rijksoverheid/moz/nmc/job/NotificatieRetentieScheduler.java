package nl.rijksoverheid.moz.nmc.job;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.FailedExecution;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.SkippedExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;
import nl.rijksoverheid.moz.nmc.repository.Kandidaat;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Verwijdert notificaties waarvan de laatste statusregistratie ouder is dan
 * {@code notificatie.retentie.bewaartermijn}, in begrensde batches met een eigen transactie per
 * batch. De bewaartermijn geldt gelijk voor elke status.
 * <p>
 * Een notificatie die verloopt zonder definitieve status krijgt er ook geen meer: NotifyNL meldt
 * niets meer terug en de NMC verwerkt hem niet verder. Dat wordt per notificatie gelogd, in dezelfde
 * transactie als de verwijdering, zodat er een aanknopingspunt overblijft nadat de rij weg is.
 * <p>
 * {@code @Startup} omdat {@code @ApplicationScoped} lazy is: zonder dit valt een ongeldige
 * bewaartermijn pas bij de eerste vuring midden in de nacht op in plaats van bij het opstarten.
 */
@Startup
@ApplicationScoped
public class NotificatieRetentieScheduler {

    // Begrenst hoeveel rijen per transactie worden verwijderd. Eén onbegrensde DELETE over een grote
    // achterstand overschrijdt de JTA-transactietimeout van 60s en rolt dan alles terug.
    private static final int BATCH_GROOTTE = 1000;

    // Een enkele mislukte batch is niet fataal, een structurele storing (DB weg, schema kapot) wel:
    // zonder deze grens probeert de run het tot maxBatches toe opnieuw.
    private static final int MAX_MISLUKTE_BATCHES_OP_RIJ = 5;

    // Begrenst de uitsluitingslijst: elke overgeslagen batch voegt tot BATCH_GROOTTE ids toe aan de
    // NOT IN-lijst van elke volgende claim.
    private static final int MAX_MISLUKTE_BATCHES_TOTAAL = 10;

    // Bij een storing bij NotifyNL kan de hele achterstand zonder eindstatus zijn; zonder deze grens
    // levert dat een logregel per notificatie op. Het totaal in de samenvatting blijft onbegrensd.
    private static final int MAX_MELDINGEN = 100;

    // Zowel de @Scheduled-identity als het trigger-id waarop de observers hieronder filteren.
    private static final String TRIGGER_ID = "notificatie-retentie";

    private final NotificatieRepository notificatieRepository;
    private final RetentieConfiguratie configuratie;

    public NotificatieRetentieScheduler(NotificatieRepository notificatieRepository, RetentieConfiguratie configuratie) {
        this.notificatieRepository = notificatieRepository;
        this.configuratie = configuratie;
    }

    // concurrentExecution = SKIP geldt per JVM: bij N pods draaien er elke nacht N volledige scans,
    // elk met eigen tellingen in de logregels hieronder. Dat mag, want elke batch is een idempotente
    // bulk-delete. Geen @Transactional op deze methode: elke batch heeft een eigen transactie, zodat
    // een latere fout de al verwijderde batches niet terugdraait.
    @Scheduled(identity = TRIGGER_ID, cron = "{notificatie.retentie.cron}",
            timeZone = "Europe/Amsterdam", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void verwijderVerlopenNotificaties() {
        OffsetDateTime grens = OffsetDateTime.now(ZoneOffset.UTC).minus(configuratie.bewaartermijn());

        int batches = 0;
        int totaalVerwijderd = 0;
        int totaalZonderEindstatus = 0;
        int gemeldeRegels = 0;
        int mislukteBatchesOpRij = 0;
        int mislukteBatchesTotaal = 0;
        boolean klaar = false;
        // Ids die deze run zijn overgeslagen omdat hun batch gooide. Zonder deze uitsluiting claimt de
        // volgende ronde exact dezelfde rijen: de rollback heeft de lock vrijgegeven.
        List<UUID> overgeslagen = new ArrayList<>();
        // try/finally zodat de samenvatting ook wordt gelogd als een batch gooit; de batches daarvóór
        // zijn dan al gecommit. De exceptie ontsnapt daarna, zodat FailedExecution blijft vuren.
        try {
            while (!klaar && batches < configuratie.maxBatches()) {
                batches++;
                BatchVoortgang voortgang = new BatchVoortgang(Math.max(0, MAX_MELDINGEN - gemeldeRegels));
                boolean geslaagd = true;
                try {
                    List<UUID> uitgesloten = List.copyOf(overgeslagen);
                    QuarkusTransaction.requiringNew()
                            .run(() -> verwijderBatch(grens, uitgesloten, voortgang));
                } catch (RuntimeException e) {
                    geslaagd = false;
                    mislukteBatchesOpRij++;
                    mislukteBatchesTotaal++;
                    overgeslagen.addAll(voortgang.geclaimd());
                    Log.errorf(e, "Retentiejob: batch %d kon niet verwijderd worden (grens=%s, %d rijen "
                            + "geclaimd) — deze rijen worden deze run overgeslagen en de job gaat door "
                            + "met de volgende batch", batches, grens, voortgang.geclaimd().size());

                    // Op rij mislukt wijst op een storing die de volgende batch net zo hard raakt.
                    // De teller gaat na elke geslaagde batch terug op nul, zodat losse
                    // onverwijderbare rijen de rest van de achterstand niet gijzelen.
                    if (mislukteBatchesOpRij >= MAX_MISLUKTE_BATCHES_OP_RIJ) {
                        Log.errorf("Retentiejob: %d batches op rij mislukt, run afgebroken; de rest van "
                                + "de achterstand is deze run niet bekeken", mislukteBatchesOpRij);

                        throw e;
                    }

                    // Begrenst de uitsluitingslijst, die als NOT IN-lijst in elke volgende claim
                    // meegaat; een run die om en om faalt en slaagt laat hem anders doorgroeien.
                    if (mislukteBatchesTotaal >= MAX_MISLUKTE_BATCHES_TOTAAL) {
                        Log.errorf("Retentiejob: %d mislukte batches in deze run, run afgebroken; %d "
                                + "rij(en) overgeslagen", mislukteBatchesTotaal, overgeslagen.size());

                        throw e;
                    }
                }

                // Buiten de try: ook een mislukte batch heeft zijn meldingen al weggeschreven, want
                // verwijderBatch meldt vóór de DELETE. Niet meetellen zou het meldbudget laten lekken.
                totaalVerwijderd += voortgang.verwijderd();
                totaalZonderEindstatus += voortgang.zonderEindstatus();
                gemeldeRegels += voortgang.gemeld();

                if (geslaagd) {
                    // Alleen opeenvolgende mislukkingen wijzen op een storing. Niet gedekt door een
                    // test: het verschil met een cumulatieve teller vraagt meer dan
                    // MAX_MISLUKTE_BATCHES_OP_RIJ verspreide mislukkingen, en dus een populatie die
                    // deze testklasse onwerkbaar traag maakt.
                    mislukteBatchesOpRij = 0;
                    Log.debugf("Retentiejob: batch %d verwijderde %d notificatie(s)", batches,
                            voortgang.verwijderd());

                    // Een batch die niet vol is, is de laatste. Wat een andere pod op dat moment
                    // vasthoudt (SKIP LOCKED) is diens werk en gaat in dezelfde nacht weg.
                    klaar = voortgang.geclaimd().size() < BATCH_GROOTTE;
                }
            }

            if (!klaar) {
                Log.errorf("Retentiejob: gestopt na de bovengrens van %d batches terwijl er nog "
                        + "verlopen notificaties waren (grens=%s), de rest blijft staan tot de "
                        + "volgende run", configuratie.maxBatches(), grens);
            }
        } finally {
            if (mislukteBatchesTotaal > 0) {
                Log.errorf("Retentiejob: %d van de %d batches mislukt, %d rij(en) deze run overgeslagen "
                        + "(grens=%s) — die notificaties zijn niet verwijderd", mislukteBatchesTotaal,
                        batches, overgeslagen.size(), grens);
            }

            if (totaalZonderEindstatus > gemeldeRegels) {
                Log.warnf("Retentiejob: alleen de eerste %d van %d notificaties zonder eindstatus zijn "
                        + "hierboven afzonderlijk gemeld", gemeldeRegels, totaalZonderEindstatus);
            }

            Log.infof("Retentiejob: %d verlopen notificatie(s) verwijderd in %d batch(es) (%d mislukt, "
                    + "%d overgeslagen), waarvan %d zonder eindstatus (grens=%s)", totaalVerwijderd,
                    batches, mislukteBatchesTotaal, overgeslagen.size(), totaalZonderEindstatus, grens);
        }
    }

    // Claimt een batch met FOR UPDATE SKIP LOCKED, meldt de notificaties zonder eindstatus en
    // verwijdert daarna precies die rijen.
    //
    // SKIP LOCKED omdat er in productie minimaal drie pods draaien: zonder die clausule blokkeren ze
    // op elkaars rijlocks in plaats van de achterstand te verdelen. De lock sluit tegelijk het gat
    // tussen claim en DELETE, want een gelijktijdige verwerkAfleverstatus wacht erop; de DELETE hoeft
    // het retentiepredicaat daarom niet te herhalen.
    //
    // Melden gebeurt vóór de DELETE en over precies dezelfde geclaimde rijen, zodat een verwijderde
    // rij nooit ongemeld blijft. Een dubbele melding na een teruggerolde batch is het alternatief.
    //
    // De DELETE is JPQL, zodat Hibernate de notificatie_status-rijen mee opruimt; de ON DELETE
    // CASCADE uit V2 blijft het vangnet voor verwijderingen buiten Hibernate om. Package-private
    // zodat de test de batchgrens rechtstreeks kan aanroepen, zonder reflectie.
    void verwijderBatch(OffsetDateTime grens, List<UUID> uitgesloten, BatchVoortgang voortgang) {
        List<UUID> ids = notificatieRepository.claimVerlopen(grens, BATCH_GROOTTE, uitgesloten);
        voortgang.noteerGeclaimd(ids);

        if (ids.isEmpty()) {
            return;
        }

        List<Kandidaat> zonderEindstatus = notificatieRepository.zoekKandidaten(ids).stream()
                .filter(kandidaat -> !kandidaat.status().isDefinitief())
                .toList();
        int gemeld = Math.min(zonderEindstatus.size(), voortgang.meldbudget());
        zonderEindstatus.subList(0, gemeld).forEach(this::meld);
        voortgang.noteerGemeld(zonderEindstatus.size(), gemeld);

        int verwijderd = notificatieRepository.verwijderOpId(ids);

        // Hoort niet te kunnen: de rijen staan onder rijlock en de DELETE gaat op precies die ids.
        // Zonder deze controle blijft de lus draaien, want klaar hangt aan het aantal geclaimde rijen;
        // als mislukte batch behandelen laat de bestaande afhandeling grijpen.
        if (verwijderd != ids.size()) {
            throw new IllegalStateException("Retentiejob: " + ids.size() + " rijen geclaimd onder "
                    + "rijlock maar " + verwijderd + " verwijderd (grens=" + grens + ")");
        }

        voortgang.noteerVerwijderd(verwijderd);
    }

    // Houder die de voortgang van één batch vasthoudt, ook als de transactie terugrolt. Een
    // retourwaarde werkt daar niet: bij een rollback is die er niet, terwijl juist dan de geclaimde
    // ids en de al weggeschreven meldingen bekend moeten zijn.
    //
    // De schrijvers zijn private, want alleen verwijderBatch vult ze; de lezers zijn
    // package-private omdat de test van de batchgrens ze ook afleest.
    static final class BatchVoortgang {

        private final int meldbudget;
        private final List<UUID> geclaimd = new ArrayList<>();
        private int zonderEindstatus;
        private int gemeld;
        private int verwijderd;

        BatchVoortgang(int meldbudget) {
            this.meldbudget = meldbudget;
        }

        int meldbudget() {
            return meldbudget;
        }

        List<UUID> geclaimd() {
            return geclaimd;
        }

        int zonderEindstatus() {
            return zonderEindstatus;
        }

        int gemeld() {
            return gemeld;
        }

        int verwijderd() {
            return verwijderd;
        }

        private void noteerGeclaimd(List<UUID> ids) {
            geclaimd.addAll(ids);
        }

        private void noteerGemeld(int zonderEindstatus, int gemeld) {
            this.zonderEindstatus = zonderEindstatus;
            this.gemeld = gemeld;
        }

        private void noteerVerwijderd(int verwijderd) {
            this.verwijderd = verwijderd;
        }
    }

    // WARN en geen ERROR: verlopen zonder eindstatus is informatie, geen storing in de NMC. Key=value
    // zodat er een dashboard op te bouwen is zonder vrije tekst te parsen; er is geen JSON-logging.
    // Op de scheduler en niet op Kandidaat, omdat Quarkus' Log de logcategorie kiest op de klasse die
    // de regel schrijft.
    private void meld(Kandidaat kandidaat) {
        // Een lege externalReference is een andere diagnose dan een gevulde: dan is de notificatie
        // nooit bij NotifyNL aangeboden, in plaats van wel aangeboden zonder uitkomst.
        Log.warnf("Retentiejob: notificatie verlopen zonder eindstatus notificatieId=%s "
                + "notifyNlReferentie=%s status=%s laatsteStatusUpdate=%s", kandidaat.id(),
                kandidaat.externalReference() != null ? kandidaat.externalReference() : "geen",
                kandidaat.status(), kandidaat.laatsteStatusUpdate());
    }

    // Vuurt voor élke @Scheduled-methode, vandaar de filtering op trigger-id. Zonder deze observer
    // wordt een overgeslagen run alleen op DEBUG gelogd, en blijft een vastgelopen run (lock-wait,
    // DB-failover) die de opruiming stopzet dus onzichtbaar.
    // Package-private zodat NotificatieRetentieSchedulerUnitTest de filtering op trigger-id kan
    // toetsen zonder een container te starten.
    void opOvergeslagenUitvoering(@Observes SkippedExecution event) {
        if (!TRIGGER_ID.equals(event.getExecution().getTrigger().getId())) {
            return;
        }

        Log.warnf("Retentiejob overgeslagen (trigger=%s, reden=%s), vorige run draait mogelijk nog vast",
                event.getExecution().getTrigger().getId(), event.getDetail());
    }

    // Zonder deze observer belandt een ontsnapte fout alleen onder de schedulerlogcategorie van
    // Quarkus en niet onder dit package, waar een operator op filtert. Al gecommitte batches blijven
    // verwijderd. Filtert op trigger-id om dezelfde reden als de observer hierboven.
    // Package-private om dezelfde reden als de observer hierboven.
    void opMislukteUitvoering(@Observes FailedExecution event) {
        if (!TRIGGER_ID.equals(event.getExecution().getTrigger().getId())) {
            return;
        }

        Log.errorf(event.getException(), "Retentiejob mislukt (trigger=%s), mogelijk niet alle "
                + "verlopen notificaties verwijderd", event.getExecution().getTrigger().getId());
    }
}
