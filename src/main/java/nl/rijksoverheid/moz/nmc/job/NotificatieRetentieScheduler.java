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
import org.eclipse.microprofile.config.inject.ConfigProperty;

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

    // Begrenst hoeveel rijen per transactie worden verwijderd: de standaard JTA-transactietimeout
    // is 60s, en een enkele onbegrensde DELETE over een grote achterstand zou die overschrijden,
    // met als gevolg een volledige rollback (dus 0 verwijderd) die de volgende nacht identiek
    // herhaald wordt, zonder ooit vanzelf te herstellen. Batches van deze grootte blijven ruim
    // binnen de timeout, ongeacht hoe groot de achterstand is.
    private static final int BATCH_GROOTTE = 1000;



    // Bovengrens op het aantal batches dat mag mislukken voordat de run alsnog opgeeft. Een batch
    // die gooit is niet vanzelf fataal — bijvoorbeeld een rij die door een toekomstige foreignkey
    // zonder cascade niet te verwijderen is — maar zonder grens zou een structurele storing (DB weg,
    // schema kapot) de job elke batch opnieuw laten proberen tot MAX_BATCHES.
    private static final int MAX_MISLUKTE_BATCHES_OP_RIJ = 5;

    // Absolute grens over de hele run, die de uitsluitingslijst begrenst: elke overgeslagen batch
    // voegt tot BATCH_GROOTTE ids toe aan de NOT IN-lijst van elke volgende claim. Tien batches is
    // 10.000 ids; komt een run daarboven, dan is het aantal onverwijderbare rijen zelf het signaal.
    private static final int MAX_MISLUKTE_BATCHES_TOTAAL = 10;

    // Bovengrens op het aantal notificaties dat per run afzonderlijk wordt gemeld. Bij een storing
    // aan de kant van NotifyNL kan de hele achterstand niet-definitief zijn; zonder deze grens zou
    // dat de logs vullen met een regel per notificatie. Het totaal in de samenvatting is niet
    // begrensd, dus een dashboard dat op dat getal telt blijft compleet.
    private static final int MAX_MELDINGEN = 100;

    // Zowel de @Scheduled-identity als het trigger-id waarop de observers hieronder filteren.
    private static final String TRIGGER_ID = "notificatie-retentie";

    private final NotificatieRepository notificatieRepository;
    private final Duration bewaartermijn;

    // Bovengrens op het aantal batches per run, tegen een onverwacht grote achterstand. De default
    // ligt ruim boven elk realistisch volume: 10.000 batches is 10 miljoen notificaties in één run.
    // Configureerbaar zodat een beheerder de run kan begrenzen zonder release, en zodat een test hem
    // kan bereiken zonder tien miljoen rijen te planten.
    private final int maxBatches;

    public NotificatieRetentieScheduler(NotificatieRepository notificatieRepository,
            @ConfigProperty(name = "notificatie.retentie.bewaartermijn") Duration bewaartermijn,
            @ConfigProperty(name = "notificatie.retentie.max-batches", defaultValue = "10000") int maxBatches) {
        if (maxBatches < 1) {
            throw new IllegalArgumentException(
                    "notificatie.retentie.max-batches moet minstens 1 zijn, maar was " + maxBatches);
        }

        this.maxBatches = maxBatches;
        // Een niet-positieve termijn is één configuratie-typefout verwijderd van "verwijder de hele
        // tabel bij de volgende run", dat hoort bij het opstarten te falen, niet stilletjes midden
        // in de nacht.
        if (bewaartermijn.isNegative() || bewaartermijn.isZero()) {
            throw new IllegalArgumentException(
                    "notificatie.retentie.bewaartermijn moet positief zijn, maar was " + bewaartermijn);
        }

        this.notificatieRepository = notificatieRepository;
        this.bewaartermijn = bewaartermijn;
    }

    // concurrentExecution = SKIP: een langlopende run mag niet overlappen met de volgende vuring.
    // Dat geldt alleen binnen één JVM/pod: bij N pods draaien er elke nacht N onafhankelijke,
    // volledige scans, elk met hun eigen (niet bij elkaar opgetelde) tellingen in de logregels
    // hieronder. Geen Quartz-clustering nodig: elke batch is een idempotente bulk-delete, dus
    // gelijktijdige pods botsen niet fataal. Wat een pod laat liggen omdat een andere er eerder bij
    // was, wordt in dezelfde nacht door die andere pod opgeruimd, of anders de eerstvolgende run.
    // Geen @Transactional op deze methode zelf: elke batch draait in zijn eigen transactie, zodat al
    // verwijderde batches niet worden teruggedraaid als een latere batch faalt.
    @Scheduled(identity = TRIGGER_ID, cron = "{notificatie.retentie.cron}",
            timeZone = "Europe/Amsterdam", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void verwijderVerlopenNotificaties() {
        OffsetDateTime grens = OffsetDateTime.now(ZoneOffset.UTC).minus(bewaartermijn);

        int batches = 0;
        int totaalVerwijderd = 0;
        int totaalZonderEindstatus = 0;
        int gemeldeRegels = 0;
        int mislukteBatchesOpRij = 0;
        int mislukteBatchesTotaal = 0;
        boolean klaar = false;
        // Ids die deze run zijn overgeslagen omdat hun batch gooide. Zonder deze uitsluiting claimt
        // de volgende ronde exact dezelfde rijen — de transactie is teruggerold, dus de lock is weg
        // en de ORDER BY levert ze opnieuw als oudste op — en blijft de job op dezelfde rij hangen.
        List<UUID> overgeslagen = new ArrayList<>();
        // try/finally zodat de samenvatting ook wordt gelogd als een batch een exceptie gooit: de
        // batches daarvóór zijn dan al gecommit, en zonder deze finally zou die (deels geslaagde)
        // voortgang nergens uit blijken. De exceptie ontsnapt daarna gewoon, zodat FailedExecution
        // blijft vuren (zie opMislukteUitvoering).
        try {
            while (!klaar && batches < maxBatches) {
                batches++;
                // Een meegegeven houder in plaats van alleen een retourwaarde: bij een rollback is er
                // geen retourwaarde, en juist dan moeten de geclaimde ids en de al weggeschreven
                // meldingen bekend zijn. Een gewone Java-lijst wordt niet teruggedraaid.
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

                    // Op rij: dat wijst op een storing die de volgende batch net zo hard raakt, dus
                    // doorgaan kost alleen tijd. De teller gaat na elke geslaagde batch terug op nul,
                    // zodat losse onverwijderbare rijen de rest van de achterstand niet gijzelen —
                    // die blijven anders elke nacht als oudste bovenkomen en de run afbreken.
                    if (mislukteBatchesOpRij >= MAX_MISLUKTE_BATCHES_OP_RIJ) {
                        Log.errorf("Retentiejob: %d batches op rij mislukt, run afgebroken; de rest van "
                                + "de achterstand is deze run niet bekeken", mislukteBatchesOpRij);

                        throw e;
                    }

                    // Totaal: begrenst de uitsluitingslijst, die als NOT IN-lijst in elke volgende
                    // claim meegaat. Zonder deze grens kan een run die om en om faalt en slaagt hem
                    // tot maxBatches/2 batches laten groeien.
                    if (mislukteBatchesTotaal >= MAX_MISLUKTE_BATCHES_TOTAAL) {
                        Log.errorf("Retentiejob: %d mislukte batches in deze run, run afgebroken; %d "
                                + "rij(en) overgeslagen", mislukteBatchesTotaal, overgeslagen.size());

                        throw e;
                    }
                }

                // Buiten de try: ook een mislukte batch heeft zijn meldingen al weggeschreven, want
                // verwijderBatch meldt vóór de DELETE. Zouden die niet meegeteld worden, dan kreeg de
                // volgende batch het volle meldbudget opnieuw en zou de samenvatting minder tellen
                // dan er detailregels onder staan.
                totaalVerwijderd += voortgang.verwijderd();
                totaalZonderEindstatus += voortgang.zonderEindstatus();
                gemeldeRegels += voortgang.gemeld();

                if (geslaagd) {
                    // Teller terug op nul: alleen opeenvolgende mislukkingen wijzen op een storing.
                    // Niet gedekt door een test — het verschil met een cumulatieve teller wordt pas
                    // zichtbaar bij meer dan MAX_MISLUKTE_BATCHES_OP_RIJ verspreide mislukkingen, en
                    // dat vraagt een populatie die deze testklasse onwerkbaar traag maakt.
                    mislukteBatchesOpRij = 0;
                    Log.debugf("Retentiejob: batch %d verwijderde %d notificatie(s)", batches,
                            voortgang.verwijderd());

                    // Een batch die niet vol is, is de laatste: er waren geen BATCH_GROOTTE rijen meer
                    // die deze pod kon claimen. Nog een ronde zou een query kosten die vrijwel zeker
                    // niets oplevert. Wat een andere pod op dat moment vasthoudt (SKIP LOCKED) is
                    // diens werk; die verwijdert het in dezelfde nacht.
                    klaar = voortgang.geclaimd().size() < BATCH_GROOTTE;
                }
            }

            if (!klaar) {
                Log.errorf("Retentiejob: gestopt na de bovengrens van %d batches terwijl er nog "
                        + "verlopen notificaties waren (grens=%s), de rest blijft staan tot de "
                        + "volgende run", maxBatches, grens);
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
    // Waarom SKIP LOCKED: in productie draaien minimaal drie pods. Zonder deze clausule selecteren
    // ze allemaal dezelfde oudste rijen en blokkeren ze op elkaars rijlocks, zodat uiteindelijk één
    // pod het werk doet terwijl de rest wacht; bij miljoenen rijen is dat elke nacht N keer dezelfde
    // scan. Met SKIP LOCKED slaat een pod over wat een ander vasthoudt en pakt hij het volgende blok,
    // zodat de pods de achterstand verdelen in plaats van erom te vechten.
    //
    // De lock haalt tegelijk de TOCTOU weg die hier eerder zat. Een gelijktijdige
    // verwerkAfleverstatus die een verse statusregel wil schrijven voor een van deze notificaties
    // blokkeert tot deze transactie commit, dus tussen de claim en de DELETE kan een kandidaat niet
    // meer van status veranderen. Het retentiepredicaat hoeft daarom niet herhaald te worden in de
    // DELETE: de lock is de garantie, niet het predicaat.
    //
    // De melding staat bewust hier en niet in een aparte fase vóór de batchlus. Zou ze apart draaien,
    // dan verwijdert deze lus alsnog de rijen waar de melding over ging als die melding faalt, en
    // dan is het feit dat ze zonder eindstatus verliepen nergens meer te achterhalen. Nu gaan melden
    // en verwijderen over precies dezelfde geclaimde rijen. Loggen vóór de DELETE, want tussen een
    // logregel en een commit bestaat geen exact-once: een dubbele melding na een teruggerolde batch
    // is ruis, een ontbrekende melding is verlies.
    //
    // ORDER BY zodat de oudste rijen eerst weggaan: past een achterstand niet in één run, dan is dat
    // de volgorde die de tabel het snelst weer normaal maakt.
    //
    // De DELETE is JPQL, zodat Hibernate de notificatie_status-rijen zelf opruimt; de ON DELETE
    // CASCADE op de foreignkey (V2) blijft het vangnet voor verwijderingen buiten Hibernate om.
    // Package-private en niet privé: NotificatieRetentieSchedulerTest roept hem rechtstreeks aan om
    // te bewijzen dat één batch écht op BATCH_GROOTTE begrensd is. Via reflectie ging dat ook, maar
    // dan breekt de test stil op elke handtekeningwijziging in plaats van bij het compileren.
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

        // De rijen staan onder rijlock en de DELETE gaat op precies die ids, dus dit hoort niet te
        // kunnen. Gebeurt het toch, dan wijzigt er iets aan notificatie buiten Hibernate om, of
        // lopen het claim- en het verwijderpredicaat uiteen. Zonder deze controle draait de lus
        // door: klaar hangt aan het aantal geclaimde rijen, dus bij nul verwijderd claimt de
        // volgende ronde exact dezelfde rijen, tot aan maxBatches. Als mislukte batch behandelen
        // laat de bestaande afhandeling grijpen: de ids gaan op de uitsluitingslijst en de run stopt
        // op tijd, met een melding die het echte probleem noemt.
        if (verwijderd != ids.size()) {
            throw new IllegalStateException("Retentiejob: " + ids.size() + " rijen geclaimd onder "
                    + "rijlock maar " + verwijderd + " verwijderd (grens=" + grens + ")");
        }

        voortgang.noteerVerwijderd(verwijderd);
    }

    // Houder die de voortgang van één batch vasthoudt, ook als de transactie terugrolt. Een
    // retourwaarde werkt daar niet: bij een rollback is die er niet, terwijl juist dan de geclaimde
    // ids en de al weggeschreven meldingen bekend moeten zijn.
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

    // Key=value in de melding zodat er een dashboard op te bouwen is zonder de regel als vrije tekst
    // te hoeven parsen; er is geen JSON-logging geconfigureerd.
    //
    // WARN en geen ERROR: dat een notificatie zonder eindstatus verloopt, is informatie en geen
    // storing in de NMC. NotifyNL meldt er niets meer over terug en de opvolging ligt buiten dit
    // component; de melding bestaat zodat het zichtbaar is in plaats van stilzwijgend te verdwijnen.
    //
    // Deze methode staat bewust op de scheduler en niet op Kandidaat: Quarkus' Log kiest de
    // logcategorie op de declarerende klasse, dus vanuit de geneste record zou de regel onder
    // NotificatieRetentieScheduler$Kandidaat verschijnen en niet onder de rest van de job.
    private void meld(Kandidaat kandidaat) {
        // Een lege externalReference is een andere diagnose dan een gevulde: dan is de notificatie
        // nooit bij NotifyNL aangeboden, in plaats van wel aangeboden zonder uitkomst.
        Log.warnf("Retentiejob: notificatie verlopen zonder eindstatus notificatieId=%s "
                + "notifyNlReferentie=%s status=%s laatsteStatusUpdate=%s", kandidaat.id(),
                kandidaat.externalReference() != null ? kandidaat.externalReference() : "geen",
                kandidaat.status(), kandidaat.laatsteStatusUpdate());
    }

    // Vuurt voor élke @Scheduled-methode in de applicatie, dus de filtering op trigger-id is nodig:
    // zonder die guard zou een overgeslagen run van een toekomstige andere job hier als "Retentiejob
    // overgeslagen" gelogd worden. Zonder deze observer wordt een overgeslagen run alleen op DEBUG
    // gelogd (standaardniveau is INFO): een vastgelopen run (lock-wait, DB-failover) zou de opruiming
    // dan stilletjes voor de rest van de levensduur van de pod stopzetten, zonder enig signaal.
    void opOvergeslagenUitvoering(@Observes SkippedExecution event) {
        if (!TRIGGER_ID.equals(event.getExecution().getTrigger().getId())) {
            return;
        }

        Log.warnf("Retentiejob overgeslagen (trigger=%s, reden=%s), vorige run draait mogelijk nog vast",
                event.getExecution().getTrigger().getId(), event.getDetail());
    }

    // Wordt aangeroepen als verwijderVerlopenNotificaties() een exceptie laat ontsnappen (bijv. uit
    // een van de batches). Zonder deze observer belandt zo'n fout alleen onder de eigen
    // schedulerlogcategorie van Quarkus, niet onder dit package, en zou een operator die op
    // nl.rijksoverheid.moz.* filtert een structureel mislukkende opruimrun nooit opmerken. Al vóór de
    // fout gecommitte batches blijven verwijderd. Filtert op trigger-id om dezelfde reden als de
    // observer hierboven.
    void opMislukteUitvoering(@Observes FailedExecution event) {
        if (!TRIGGER_ID.equals(event.getExecution().getTrigger().getId())) {
            return;
        }

        Log.errorf(event.getException(), "Retentiejob mislukt (trigger=%s), mogelijk niet alle "
                + "verlopen notificaties verwijderd", event.getExecution().getTrigger().getId());
    }
}
