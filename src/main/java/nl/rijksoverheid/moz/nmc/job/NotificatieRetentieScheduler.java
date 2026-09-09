package nl.rijksoverheid.moz.nmc.job;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.FailedExecution;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.SkippedExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import nl.rijksoverheid.moz.nmc.domain.Notificatie_;
import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Ruimt Notificatie-rijen op waarvan de status langer dan de bewaartermijn niet is bijgewerkt.
 * Losgekoppeld van het afleveren van statussen aan de Dienstverlener (zowel via callback als een
 * eventuele toekomstige GET): die voegen geen statusregel toe en verzetten de bewaartermijn dus
 * niet. Elke registratie in de statusgeschiedenis doet dat wél, ook de NMC-eigen registraties
 * (CREATED in de constructor van Notificatie, SENDING in NotificatieService#verstuurNaarEmail), niet
 * alleen een binnenkomende NotifyNL-statusupdate. Een notificatie waarover NotifyNL nooit iets
 * terugmeldt verloopt dus de bewaartermijn na zijn eigen SENDING-registratie. De bewaartermijn geldt
 * gelijk voor elke status; StatusWaarde#isDefinitief bepaalt hier alleen welke verlopen notificaties
 * apart gemeld worden (zie meldNietDefinitieveKandidaten).
 */
// @Startup: @ApplicationScoped beans zijn standaard lazy, zonder dit forceert Quarkus de
// constructor pas bij de eerste @Scheduled-vuring, dus een ongeldige bewaartermijn zou pas midden in
// de nacht aan het licht komen (zie de constructor) in plaats van bij het opstarten.
@Startup
@ApplicationScoped
public class NotificatieRetentieScheduler {

    // Begrenst hoeveel rijen per transactie worden verwijderd: de standaard JTA-transactietimeout
    // is 60s, en een enkele onbegrensde DELETE over een grote achterstand zou die overschrijden,
    // met als gevolg een volledige rollback (dus 0 verwijderd) die de volgende nacht identiek
    // herhaald wordt, zonder ooit vanzelf te herstellen. Batches van deze grootte blijven ruim
    // binnen de timeout, ongeacht hoe groot de achterstand is.
    private static final int BATCH_GROOTTE = 1000;

    // Bovengrens op het aantal batches per run. De lus stopt normaal vanzelf zodra een batch niets
    // meer verwijdert; deze grens is er voor het geval een rij structureel niet weg te krijgen is
    // (bijv. een toekomstige foreignkey zonder cascade die de DELETE laat falen), zodat de job dan
    // niet eindeloos dezelfde pagina blijft ophalen. Ruim boven elke realistische achterstand:
    // 10.000 batches is 10 miljoen notificaties in één run.
    private static final int MAX_BATCHES = 10_000;

    // Bovengrens op het aantal notificaties dat per run afzonderlijk wordt gemeld. Bij een storing
    // aan de kant van NotifyNL kan de hele achterstand niet-definitief zijn; zonder deze grens zou
    // dat de logs vullen met een regel per notificatie.
    private static final int MAX_MELDINGEN = 100;

    private static final List<StatusWaarde> NIET_DEFINITIEVE_STATUSSEN = Arrays.stream(StatusWaarde.values())
            .filter(status -> !status.isDefinitief())
            .toList();

    // Zowel de @Scheduled-identity als het trigger-id waarop de observers hieronder filteren.
    private static final String TRIGGER_ID = "notificatie-retentie";

    private final NotificatieRepository notificatieRepository;
    private final Duration bewaartermijn;

    public NotificatieRetentieScheduler(NotificatieRepository notificatieRepository,
            @ConfigProperty(name = "notificatie.retentie.bewaartermijn", defaultValue = "30d") Duration bewaartermijn) {
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

        meldNietDefinitieveKandidaten(grens);

        int batches = 0;
        int totaalVerwijderd = 0;
        boolean klaar = false;
        // try/finally zodat de samenvatting ook wordt gelogd als een batch een exceptie gooit: de
        // batches daarvóór zijn dan al gecommit, en zonder deze finally zou die (deels geslaagde)
        // voortgang nergens uit blijken. De exceptie ontsnapt daarna gewoon, zodat FailedExecution
        // blijft vuren (zie opMislukteUitvoering).
        try {
            while (!klaar && batches < MAX_BATCHES) {
                batches++;
                int verwijderd = QuarkusTransaction.requiringNew().call(() -> verwijderBatch(grens));
                totaalVerwijderd += verwijderd;
                Log.debugf("Retentiejob: batch %d verwijderde %d notificatie(s)", batches, verwijderd);

                // 0 verwijderd betekent ofwel niets meer te doen, ofwel dat de kandidaten van deze
                // batch niet weg te krijgen zijn (andere pod was eerder, of een rij die structureel
                // blijft staan). In beide gevallen heeft dóórgaan binnen deze run geen zin.
                klaar = verwijderd == 0;
            }

            if (!klaar) {
                Log.errorf("Retentiejob: gestopt na de bovengrens van %d batches terwijl er nog "
                        + "verlopen notificaties waren (grens=%s), mogelijk een rij die niet "
                        + "verwijderd kan worden", MAX_BATCHES, grens);
            }
        } finally {
            Log.infof("Retentiejob: %d verlopen notificatie(s) verwijderd in %d batch(es) (grens=%s)",
                    totaalVerwijderd, batches, grens);
        }
    }

    // Meldt de verlopen notificaties die nooit een eindstatus van NotifyNL hebben gekregen, vóór de
    // verwijdering: de melding gaat over het feit dat ze verlopen zijn zonder uitkomst, niet over de
    // verwijdering zelf. Draait één keer per run in plaats van per batch, en selecteert alleen de
    // niet-definitieve statussen, zodat er nooit een kandidatenlijst van de hele achterstand in het
    // geheugen komt. Eerst tellen, dan pas de detailregels ophalen: in de normale situatie (niets
    // niet-definitief) kost dat één query, en het totaal blijft ook zichtbaar als de detailregels
    // worden afgekapt.
    //
    // Alles in een eigen try/catch: dit is signalering, geen opruiming. Zou een storing hier de
    // exceptie laten ontsnappen, dan werd er die nacht geen enkele batch verwijderd terwijl de
    // achterstand doorgroeit, precies de koppeling die deze job juist wil vermijden.
    private void meldNietDefinitieveKandidaten(OffsetDateTime grens) {
        try {
            long totaal = QuarkusTransaction.requiringNew().call(() -> telNietDefinitieveKandidaten(grens));

            if (totaal == 0) {
                return;
            }

            Log.warnf("Retentiejob: %d verlopen notificatie(s) zonder definitieve status (grens=%s), "
                    + "nooit een eindstatus van NotifyNL ontvangen", totaal, grens);

            List<Kandidaat> kandidaten = QuarkusTransaction.requiringNew()
                    .call(() -> zoekNietDefinitieveKandidaten(grens));
            kandidaten.forEach(kandidaat -> Log.warnf("Retentiejob: notificatie %s (NotifyNL-referentie "
                    + "%s) is verlopen met niet-definitieve status %s", kandidaat.id(),
                    kandidaat.externalReference(), kandidaat.status()));

            if (totaal > kandidaten.size()) {
                Log.warnf("Retentiejob: alleen de %d oudste van %d zijn hierboven per notificatie gemeld",
                        kandidaten.size(), totaal);
            }
        } catch (RuntimeException e) {
            Log.error("Retentiejob: melden van niet-definitieve kandidaten mislukt, de opruiming "
                    + "gaat door", e);
        }
    }

    private long telNietDefinitieveKandidaten(OffsetDateTime grens) {
        return notificatieRepository.getEntityManager()
                .createQuery("SELECT COUNT(n) FROM Notificatie n WHERE n."
                        + Notificatie_.LAATSTE_STATUS_UPDATE + " <= :grens AND n."
                        + Notificatie_.LAATSTE_STATUS + " IN :statussen", Long.class)
                .setParameter("grens", grens)
                .setParameter("statussen", NIET_DEFINITIEVE_STATUSSEN)
                .getSingleResult();
    }

    // Oudste eerst, zodat de afgekapte lijst een reproduceerbare en bruikbare selectie is (de langst
    // vastzittende notificaties) in plaats van een willekeurige greep. externalReference wordt
    // meegelezen omdat een melding met alleen het id niet te herleiden is tot een NotifyNL-notificatie
    // om te debuggen.
    private List<Kandidaat> zoekNietDefinitieveKandidaten(OffsetDateTime grens) {
        List<Object[]> rijen = notificatieRepository.getEntityManager()
                .createQuery("SELECT n.id, n." + Notificatie_.EXTERNAL_REFERENCE + ", n."
                        + Notificatie_.LAATSTE_STATUS + " FROM Notificatie n WHERE n."
                        + Notificatie_.LAATSTE_STATUS_UPDATE + " <= :grens AND n."
                        + Notificatie_.LAATSTE_STATUS + " IN :statussen ORDER BY n."
                        + Notificatie_.LAATSTE_STATUS_UPDATE, Object[].class)
                .setParameter("grens", grens)
                .setParameter("statussen", NIET_DEFINITIEVE_STATUSSEN)
                .setMaxResults(MAX_MELDINGEN)
                .getResultList();

        return rijen.stream()
                .map(rij -> new Kandidaat((UUID) rij[0], (UUID) rij[1], (StatusWaarde) rij[2]))
                .toList();
    }

    // Selecteert een begrensd aantal ids (begrensd via setMaxResults, dat Hibernate per dialect naar
    // LIMIT/FETCH FIRST vertaalt, JPQL zelf kent geen draagbare LIMIT-syntax) en verwijdert die
    // vervolgens via een JPQL bulk-delete. Blijft bewust JPQL in plaats van native SQL: Hibernate
    // ruimt de @ElementCollection-rijen dan zelf op (aangetoond op H2 in
    // NotificatieRetentieSchedulerTest, dat een andere mutation-strategy gebruikt dan Postgres; op
    // Postgres is de ON DELETE CASCADE-foreignkey het vangnet).
    private int verwijderBatch(OffsetDateTime grens) {
        List<UUID> ids = notificatieRepository.getEntityManager()
                .createQuery("SELECT n.id FROM Notificatie n WHERE n."
                        + Notificatie_.LAATSTE_STATUS_UPDATE + " <= :grens", UUID.class)
                .setParameter("grens", grens)
                .setMaxResults(BATCH_GROOTTE)
                .getResultList();

        if (ids.isEmpty()) {
            return 0;
        }

        int verwijderd = verwijder(ids, grens);

        if (verwijderd < ids.size()) {
            // Zichtbaar maken wat anders geruisloos verdwijnt: tussen de SELECT en de DELETE is voor
            // een of meer kandidaten iets veranderd, een andere pod was eerder, of er kwam net een
            // nieuwe statusregel binnen waardoor de notificatie niet meer verlopen is (die wordt door
            // de predicaatherhaling in de DELETE hieronder bewust overgeslagen).
            Log.warnf("Retentiejob: %d van de %d kandidaten in deze batch zijn niet verwijderd, "
                    + "gelijktijdig al opgeruimd of niet meer verlopen (grens=%s)",
                    ids.size() - verwijderd, ids.size(), grens);
        }

        return verwijderd;
    }

    // De DELETE herhaalt het retentiepredicaat uit de kandidatenquery in plaats van blind op id te
    // verwijderen: tussen beide statements door kan een gelijktijdige verwerkAfleverstatus een verse
    // statusregel gecommit hebben, waardoor de notificatie niet meer verlopen is. Een bulk-delete
    // gaat volledig buiten de persistence context om en toetst dus géén @Version, de optimistic
    // locking op Notificatie beschermt hier niets, alleen het predicaat in dit statement doet dat.
    private int verwijder(Collection<UUID> ids, OffsetDateTime grens) {
        return notificatieRepository.getEntityManager()
                .createQuery("DELETE FROM Notificatie n WHERE n.id IN :ids AND n."
                        + Notificatie_.LAATSTE_STATUS_UPDATE + " <= :grens")
                .setParameter("ids", ids)
                .setParameter("grens", grens)
                .executeUpdate();
    }

    record Kandidaat(UUID id, UUID externalReference, StatusWaarde status) {
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
