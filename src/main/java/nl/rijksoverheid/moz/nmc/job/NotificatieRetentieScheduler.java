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
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.query.NativeQuery;

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

    // Bovengrens op het aantal batches per run, tegen een onverwacht grote achterstand. Ruim boven
    // elk realistisch volume: 10.000 batches is 10 miljoen notificaties in één run.
    private static final int MAX_BATCHES = 10_000;

    // Bovengrens op het aantal batches dat mag mislukken voordat de run alsnog opgeeft. Een batch
    // die gooit is niet vanzelf fataal — bijvoorbeeld een rij die door een toekomstige foreignkey
    // zonder cascade niet te verwijderen is — maar zonder grens zou een structurele storing (DB weg,
    // schema kapot) de job elke batch opnieuw laten proberen tot MAX_BATCHES.
    private static final int MAX_MISLUKTE_BATCHES = 5;

    // Bovengrens op het aantal notificaties dat per run afzonderlijk wordt gemeld. Bij een storing
    // aan de kant van NotifyNL kan de hele achterstand niet-definitief zijn; zonder deze grens zou
    // dat de logs vullen met een regel per notificatie. Het totaal in de samenvatting is niet
    // begrensd, dus een dashboard dat op dat getal telt blijft compleet.
    private static final int MAX_MELDINGEN = 100;

    // Zowel de @Scheduled-identity als het trigger-id waarop de observers hieronder filteren.
    private static final String TRIGGER_ID = "notificatie-retentie";

    // Zie verwijderBatch. Native SQL omdat JPQL geen lock-clausule met SKIP LOCKED kent.
    // Haalt meteen de velden op die de melding nodig heeft, zodat melden en verwijderen over exact
    // dezelfde rijen gaan.
    private static final String CLAIM_BATCH_SQL = """
            SELECT id
              FROM notificatie
             WHERE laatste_status_update <= ?1
             ORDER BY laatste_status_update
             FETCH FIRST ?2 ROWS ONLY
               FOR UPDATE SKIP LOCKED
            """;

    // Variant die eerder mislukte rijen overslaat; zie de lus in verwijderVerlopenNotificaties.
    private static final String CLAIM_BATCH_MET_UITSLUITING_SQL = """
            SELECT id
              FROM notificatie
             WHERE laatste_status_update <= ?1
               AND id NOT IN (?3)
             ORDER BY laatste_status_update
             FETCH FIRST ?2 ROWS ONLY
               FOR UPDATE SKIP LOCKED
            """;

    private final NotificatieRepository notificatieRepository;
    private final Duration bewaartermijn;

    public NotificatieRetentieScheduler(NotificatieRepository notificatieRepository,
            @ConfigProperty(name = "notificatie.retentie.bewaartermijn") Duration bewaartermijn) {
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
        int mislukteBatches = 0;
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
            while (!klaar && batches < MAX_BATCHES) {
                batches++;
                int budget = Math.max(0, MAX_MELDINGEN - gemeldeRegels);
                // Gevuld door verwijderBatch vóór de DELETE, zodat de ids ook na een rollback bekend
                // zijn: een gewone Java-lijst wordt niet teruggedraaid.
                List<UUID> geclaimd = new ArrayList<>();
                BatchResultaat resultaat;
                try {
                    List<UUID> uitgesloten = List.copyOf(overgeslagen);
                    resultaat = QuarkusTransaction.requiringNew()
                            .call(() -> verwijderBatch(grens, budget, uitgesloten, geclaimd));
                } catch (RuntimeException e) {
                    mislukteBatches++;
                    overgeslagen.addAll(geclaimd);
                    Log.errorf(e, "Retentiejob: batch %d kon niet verwijderd worden (grens=%s, %d rijen "
                            + "geclaimd) — deze rijen worden deze run overgeslagen en de job gaat door "
                            + "met de volgende batch", batches, grens, geclaimd.size());

                    if (mislukteBatches >= MAX_MISLUKTE_BATCHES) {
                        Log.errorf("Retentiejob: %d batches op rij mislukt, run afgebroken",
                                mislukteBatches);

                        throw e;
                    }

                    continue;
                }

                totaalVerwijderd += resultaat.verwijderd();
                totaalZonderEindstatus += resultaat.zonderEindstatus();
                gemeldeRegels += resultaat.gemeld();
                Log.debugf("Retentiejob: batch %d verwijderde %d notificatie(s)", batches, resultaat.verwijderd());

                // Een batch die niet vol is, is de laatste: er waren geen BATCH_GROOTTE rijen meer
                // die deze pod kon claimen. Nog een ronde zou een query kosten die vrijwel zeker
                // niets oplevert. Wat een andere pod op dat moment vasthoudt (SKIP LOCKED) is diens
                // werk; die verwijdert het in dezelfde nacht.
                klaar = resultaat.geclaimd() < BATCH_GROOTTE;
            }

            if (!klaar) {
                Log.errorf("Retentiejob: gestopt na de bovengrens van %d batches terwijl er nog "
                        + "verlopen notificaties waren (grens=%s), mogelijk een rij die niet "
                        + "verwijderd kan worden", MAX_BATCHES, grens);
            }
        } finally {
            if (totaalZonderEindstatus > gemeldeRegels) {
                Log.warnf("Retentiejob: alleen de eerste %d van %d notificaties zonder eindstatus zijn "
                        + "hierboven afzonderlijk gemeld", gemeldeRegels, totaalZonderEindstatus);
            }
            Log.infof("Retentiejob: %d verlopen notificatie(s) verwijderd in %d batch(es), waarvan %d "
                    + "zonder eindstatus (grens=%s)", totaalVerwijderd, batches, totaalZonderEindstatus, grens);
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
    private BatchResultaat verwijderBatch(OffsetDateTime grens, int meldbudget, List<UUID> uitgesloten,
            List<UUID> geclaimd) {
        List<UUID> ids = claimBatch(grens, uitgesloten);
        geclaimd.addAll(ids);

        if (ids.isEmpty()) {
            return new BatchResultaat(0, 0, 0, 0);
        }

        List<Kandidaat> zonderEindstatus = zoekKandidaten(ids).stream()
                .filter(kandidaat -> !kandidaat.status().isDefinitief())
                .toList();
        int gemeld = Math.min(zonderEindstatus.size(), meldbudget);
        zonderEindstatus.subList(0, gemeld).forEach(this::meld);

        int verwijderd = notificatieRepository.getEntityManager()
                .createQuery("DELETE FROM Notificatie n WHERE n.id IN :ids")
                .setParameter("ids", ids)
                .executeUpdate();

        return new BatchResultaat(ids.size(), verwijderd, zonderEindstatus.size(), gemeld);
    }

    // Alleen het id komt uit de native query. Native is nodig voor de lock-clausule, en native
    // betekent positionele casts die de compiler niet bewaakt — hoe minder kolommen daar doorheen
    // gaan, hoe kleiner dat oppervlak. addScalar blijft wél nodig: een uuid-kolom komt per dialect
    // anders terug (PostgreSQL een UUID, H2 een byte[]).
    @SuppressWarnings("unchecked")
    private List<UUID> claimBatch(OffsetDateTime grens, List<UUID> uitgesloten) {
        NativeQuery<UUID> query = notificatieRepository.getEntityManager()
                .createNativeQuery(uitgesloten.isEmpty() ? CLAIM_BATCH_SQL : CLAIM_BATCH_MET_UITSLUITING_SQL)
                .unwrap(NativeQuery.class)
                .addScalar("id", UUID.class)
                .setParameter(1, grens)
                .setParameter(2, BATCH_GROOTTE);

        if (!uitgesloten.isEmpty()) {
            query.setParameter(3, uitgesloten);
        }

        return query.getResultList();
    }

    // De gegevens voor de melding via een JPQL-constructorexpressie in plaats van uit de native
    // query. Hibernate valideert die expressie bij het opstarten: een verkeerde ariteit of een type
    // dat niet op de recordcomponent past is dan een opstartfout, in plaats van een
    // ClassCastException in een nachtelijke job. Dat is sterker dan een test, want het is niet uit
    // te zetten.
    //
    // Kost een extra query per niet-lege batch: een IN op de primary key van rijen die net gelockt
    // zijn en dus in de buffer cache staan. Bij de batchaantallen hier valt dat weg.
    //
    // ORDER BY omdat IN geen volgorde garandeert, terwijl de afgekapte melding de oudste rijen hoort
    // te tonen en niet een willekeurige greep.
    private List<Kandidaat> zoekKandidaten(List<UUID> ids) {
        return notificatieRepository.getEntityManager()
                .createQuery("SELECT new nl.rijksoverheid.moz.nmc.job.Kandidaat(n.id, n.externalReference, "
                        + "n.laatsteStatus.status, n.laatsteStatus.geregistreerd) FROM Notificatie n "
                        + "WHERE n.id IN :ids ORDER BY n.laatsteStatus.geregistreerd", Kandidaat.class)
                .setParameter("ids", ids)
                .getResultList();
    }

    record BatchResultaat(int geclaimd, int verwijderd, int zonderEindstatus, int gemeld) {
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
