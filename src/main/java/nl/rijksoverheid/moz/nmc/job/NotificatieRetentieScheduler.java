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
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus_;
import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Ruimt Notificatie-rijen op waarvan de status langer dan de bewaartermijn niet is bijgewerkt.
 * Losgekoppeld van het afleveren van statussen aan de Dienstverlener (zowel via callback als een
 * eventuele toekomstige GET): die voegen geen statusregel toe en verzetten de bewaartermijn dus
 * niet. Elke registratie in de statusgeschiedenis doet dat wél — ook de NMC-eigen registraties
 * (CREATED in de constructor van Notificatie, SENDING in NotificatieService#verstuurNaarEmail), niet
 * alleen een binnenkomende NotifyNL-statusupdate. Een notificatie waarover NotifyNL nooit iets
 * terugmeldt verloopt dus de bewaartermijn na zijn eigen SENDING-registratie. De bewaartermijn geldt
 * gelijk voor elke status; StatusWaarde#isDefinitief bepaalt hier alleen op welk niveau een
 * verwijdering gelogd wordt (zie verwijderVerlopenNotificaties).
 */
// @Startup: @ApplicationScoped beans zijn standaard lazy — zonder dit forceert Quarkus de
// constructor pas bij de eerste @Scheduled-vuring, dus een ongeldige bewaartermijn zou pas om 3 uur
// 's nachts aan het licht komen (zie de constructor) in plaats van bij het opstarten.
@Startup
@ApplicationScoped
public class NotificatieRetentieScheduler {

    // Begrenst hoeveel rijen per transactie worden verwijderd: de standaard JTA-transactietimeout
    // is 60s, en een enkele onbegrensde DELETE over een grote achterstand zou die overschrijden —
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

    // Zowel de @Scheduled-identity als het trigger-id waarop de observers hieronder filteren.
    private static final String TRIGGER_ID = "notificatie-retentie";

    private final NotificatieRepository notificatieRepository;
    private final Duration bewaartermijn;

    public NotificatieRetentieScheduler(NotificatieRepository notificatieRepository,
            @ConfigProperty(name = "notificatie.retentie.bewaartermijn", defaultValue = "30d") Duration bewaartermijn) {
        // Een niet-positieve termijn is één configuratie-typefout verwijderd van "verwijder de hele
        // tabel bij de volgende run" — dat hoort bij het opstarten te falen, niet stilletjes om 3
        // uur 's nachts.
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
    // gelijktijdige pods botsen niet fataal — de tragere pod treft een batch aan waarin een andere
    // pod al eerst was en verwijdert er 0, en beëindigt daarop zijn run (zie de lus hieronder). Wat
    // die pod dan laat liggen is niet verloren: de snellere pod ruimt het in dezelfde nacht op, of
    // anders de eerstvolgende run.
    // Geen @Transactional op deze methode zelf: elke batch draait in zijn eigen transactie, zodat al
    // verwijderde batches niet worden teruggedraaid als een latere batch faalt.
    @Scheduled(identity = TRIGGER_ID, cron = "{notificatie.retentie.cron}",
            timeZone = "Europe/Amsterdam", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void verwijderVerlopenNotificaties() {
        OffsetDateTime grens = OffsetDateTime.now(ZoneOffset.UTC).minus(bewaartermijn);

        int batches = 0;
        int totaalKandidaten = 0;
        int totaalVerwijderd = 0;
        int totaalNietDefinitief = 0;
        boolean klaar = false;
        // try/finally zodat de samenvatting ook wordt gelogd als een batch een exceptie gooit: de
        // batches daarvóór zijn dan al gecommit, en zonder deze finally zou die (deels geslaagde)
        // voortgang nergens uit blijken. De exceptie ontsnapt daarna gewoon, zodat FailedExecution
        // blijft vuren (zie opMislukteUitvoering).
        try {
            while (!klaar && batches < MAX_BATCHES) {
                batches++;
                BatchResultaat resultaat = QuarkusTransaction.requiringNew().call(() -> verwijderBatch(grens));
                totaalKandidaten += resultaat.kandidaten();
                totaalVerwijderd += resultaat.verwijderd();
                totaalNietDefinitief += resultaat.nietDefinitiefKandidaten();
                Log.debugf("Retentiejob: batch %d verwijderde %d van %d kandidaten", batches,
                        resultaat.verwijderd(), resultaat.kandidaten());

                // De lus vaart op het aantal verwijderde rijen, niet op de paginagrootte: door de
                // deduplicatie in verwijderBatch kan een volle kandidatenpagina minder distincte
                // notificaties opleveren dan BATCH_GROOTTE, terwijl er nog wel werk resteert.
                // 0 verwijderd betekent ofwel niets meer te doen, ofwel dat de kandidaten van deze
                // batch niet weg te krijgen zijn (andere pod was eerder, of een rij die structureel
                // blijft staan) — in beide gevallen heeft dóórgaan binnen deze run geen zin.
                klaar = resultaat.verwijderd() == 0;
            }

            if (!klaar) {
                Log.errorf("Retentiejob: gestopt na de bovengrens van %d batches terwijl er nog "
                        + "verlopen notificaties waren (grens=%s) — mogelijk een rij die niet "
                        + "verwijderd kan worden", MAX_BATCHES, grens);
            }
        } finally {
            Log.infof("Retentiejob: %d verlopen notificatie(s) verwijderd in %d batch(es) (grens=%s)",
                    totaalVerwijderd, batches, grens);

            if (totaalNietDefinitief > 0) {
                // Kan wijzen op een notificatie waarvoor NotifyNL nooit een (eind)statusupdate heeft
                // gestuurd — de moeite van het uitzoeken waard. De bewaartermijn zelf maakt hier geen
                // onderscheid; dit is puur signalerend. Teller én noemer gaan over dezelfde populatie
                // (de kandidaten die de SELECT vond), niet over het aantal daadwerkelijk verwijderde
                // rijen: die twee kunnen uiteenlopen als een andere pod er eerder bij was.
                Log.warnf("Retentiejob: %d van de %d kandidaten hadden nog geen definitieve status "
                        + "(grens=%s) — nooit een eindstatus van NotifyNL ontvangen",
                        totaalNietDefinitief, totaalKandidaten, grens);
            }
        }
    }

    // Selecteert eerst een begrensd aantal kandidaten (begrensd via setMaxResults, dat Hibernate per
    // dialect naar LIMIT/FETCH FIRST vertaalt — JPQL zelf kent geen draagbare LIMIT-syntax) en
    // verwijdert die vervolgens via een JPQL bulk-delete op id — zo blijft dit een JPQL-statement
    // (Hibernate ruimt de @ElementCollection-rijen dan zelf op — aangetoond op H2 in
    // NotificatieRetentieSchedulerTest, dat een andere mutation-strategy gebruikt dan Postgres; op
    // Postgres is de ON DELETE CASCADE-foreignkey het vangnet) in plaats van native SQL. De laatste
    // statusregel per notificatie wordt via een op n gecorreleerde subquery (MAX-tijdstip binnen
    // n.statusGeschiedenis) afgeleid, zodat de definitief/niet-definitief-telling voor de WARN-log
    // op precies dezelfde rijen gebeurt als waarop ook echt verwijderd wordt — geen aparte (en dus
    // potentieel inconsistente) query. externalReference wordt meegelezen zodat een niet-definitieve
    // verwijdering per notificatie gelogd kan worden (zie hieronder) — met alleen het id is zo'n
    // regel niet te herleiden tot een NotifyNL-notificatie om te debuggen.
    private BatchResultaat verwijderBatch(OffsetDateTime grens) {
        List<Object[]> kandidaatRijen = notificatieRepository.getEntityManager()
                .createQuery("SELECT n.id, n." + Notificatie_.EXTERNAL_REFERENCE + ", ns."
                        + NotificatieStatus_.STATUS + " FROM Notificatie n JOIN n." + Notificatie_.STATUS_GESCHIEDENIS
                        + " ns WHERE ns." + NotificatieStatus_.TIJDSTIP + " <= :grens AND ns."
                        + NotificatieStatus_.TIJDSTIP + " = (SELECT MAX(ns2." + NotificatieStatus_.TIJDSTIP
                        + ") FROM n." + Notificatie_.STATUS_GESCHIEDENIS + " ns2) ORDER BY ns."
                        + NotificatieStatus_.TIJDSTIP, Object[].class)
                .setParameter("grens", grens)
                .setMaxResults(BATCH_GROOTTE)
                .getResultList();

        if (kandidaatRijen.isEmpty()) {
            return new BatchResultaat(0, 0, 0);
        }

        // De MAX-subquery filtert niet per se op een unieke waarde: heeft een notificatie twee
        // statusregels met hetzelfde tijdstip, dan matcht de subquery ze allebei en levert de JOIN
        // meer dan één rij voor die notificatie op. Dedupliceren op id houdt de telling en de
        // verwijdering hieronder correct ongeacht of dat voorkomt. Bij zo'n gelijkspel telt de
        // notificatie als definitief zodra één van de gelijktijdige statussen dat is — welke van de
        // (eventueel meerdere) definitieve statussen precies bewaard blijft is dan verder een
        // willekeurige keuze, want dat verandert niets aan de definitief/niet-definitief-telling.
        Map<UUID, Kandidaat> laatsteStatusPerNotificatie = kandidaatRijen.stream()
                .collect(Collectors.toMap(rij -> (UUID) rij[0],
                        rij -> new Kandidaat((UUID) rij[1], (StatusWaarde) rij[2]),
                        (eerste, tweede) -> eerste.status().isDefinitief() ? eerste : tweede,
                        LinkedHashMap::new));

        // Per notificatie gelogd (i.p.v. alleen de aggregaatteller hieronder) zodat een niet-
        // definitieve verwijdering met de NotifyNL-referentie te herleiden/debuggen is.
        laatsteStatusPerNotificatie.forEach((id, kandidaat) -> {
            if (!kandidaat.status().isDefinitief()) {
                Log.warnf("Retentiejob: notificatie %s (NotifyNL-referentie %s) verwijderd met "
                        + "niet-definitieve status %s — nooit een eindstatus van NotifyNL ontvangen",
                        id, kandidaat.externalReference(), kandidaat.status());
            }
        });

        int nietDefinitiefKandidaten = (int) laatsteStatusPerNotificatie.values().stream()
                .filter(kandidaat -> !kandidaat.status().isDefinitief())
                .count();

        int kandidaten = laatsteStatusPerNotificatie.size();
        int verwijderd = verwijder(laatsteStatusPerNotificatie.keySet(), grens);

        if (verwijderd < kandidaten) {
            // Zichtbaar maken wat anders geruisloos verdwijnt: tussen de SELECT en de DELETE is voor
            // een of meer kandidaten iets veranderd — een andere pod was eerder, of er kwam net een
            // nieuwe statusregel binnen waardoor de notificatie niet meer verlopen is (die wordt door
            // de predicaatherhaling in de DELETE hieronder bewust overgeslagen).
            Log.warnf("Retentiejob: %d van de %d kandidaten in deze batch zijn niet verwijderd — "
                    + "gelijktijdig al opgeruimd of niet meer verlopen (grens=%s)",
                    kandidaten - verwijderd, kandidaten, grens);
        }

        return new BatchResultaat(kandidaten, verwijderd, nietDefinitiefKandidaten);
    }

    // De DELETE herhaalt het retentiepredicaat uit de kandidatenquery in plaats van blind op id te
    // verwijderen: tussen beide statements door kan een gelijktijdige verwerkAfleverstatus een verse
    // statusregel gecommit hebben, waardoor de notificatie niet meer verlopen is. Een bulk-delete
    // gaat volledig buiten de persistence context om en toetst dus géén @Version — de optimistic
    // locking op Notificatie beschermt hier niets, alleen het predicaat in dit statement doet dat.
    //
    // Geformuleerd als "geen enkele statusregel jonger dan de grens" i.p.v. als de MAX-vorm uit de
    // kandidatenquery. Beide zijn gelijkwaardig zolang er minstens één statusregel is — en dat is
    // hier zo, want de ids komen uit die kandidatenquery, die op een statusregel joint. De MAX-vorm
    // (ook in zijn EXISTS- en gecorreleerde varianten) overleeft Hibernates herschrijving van een
    // bulk-delete naar een id-tabelstrategie niet: het statement draait dan zonder fout, maar matcht
    // niets. Deze vorm profiteert bovendien van de index op notificatie_status(tijdstip).
    private int verwijder(Collection<UUID> ids, OffsetDateTime grens) {
        return notificatieRepository.getEntityManager()
                .createQuery("DELETE FROM Notificatie n WHERE n.id IN :ids AND NOT EXISTS ("
                        + "SELECT 1 FROM Notificatie n2 JOIN n2." + Notificatie_.STATUS_GESCHIEDENIS
                        + " ns WHERE n2.id = n.id AND ns." + NotificatieStatus_.TIJDSTIP + " > :grens)")
                .setParameter("ids", ids)
                .setParameter("grens", grens)
                .executeUpdate();
    }

    private record Kandidaat(UUID externalReference, StatusWaarde status) {
    }

    // kandidaten en nietDefinitiefKandidaten gaan allebei over de gededupliceerde kandidaten die de
    // SELECT vond; verwijderd over wat de DELETE daadwerkelijk weghaalde. Die populaties mogen niet
    // door elkaar gehaald worden in een logregel: ze lopen uiteen zodra een andere pod eerder was.
    record BatchResultaat(int kandidaten, int verwijderd, int nietDefinitiefKandidaten) {

        BatchResultaat {
            if (kandidaten < 0 || verwijderd < 0 || nietDefinitiefKandidaten < 0) {
                throw new IllegalArgumentException("Aantallen mogen niet negatief zijn, maar waren "
                        + "kandidaten=" + kandidaten + ", verwijderd=" + verwijderd
                        + ", nietDefinitiefKandidaten=" + nietDefinitiefKandidaten);
            }
            if (nietDefinitiefKandidaten > kandidaten) {
                throw new IllegalArgumentException("nietDefinitiefKandidaten (" + nietDefinitiefKandidaten
                        + ") kan niet groter zijn dan kandidaten (" + kandidaten + ")");
            }
        }
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

        Log.warnf("Retentiejob overgeslagen (trigger=%s, reden=%s) — vorige run draait mogelijk nog vast",
                event.getExecution().getTrigger().getId(), event.getDetail());
    }

    // Wordt aangeroepen als verwijderVerlopenNotificaties() een exceptie laat ontsnappen (bijv. uit
    // een van de batches) — zonder deze observer belandt zo'n fout alleen onder Quarkus' eigen
    // schedulerlogcategorie, niet onder dit package, en zou een operator die op
    // nl.rijksoverheid.moz.* filtert een structureel mislukkende opruimrun nooit opmerken. Al vóór de
    // fout gecommitte batches blijven verwijderd. Filtert op trigger-id om dezelfde reden als de
    // observer hierboven.
    void opMislukteUitvoering(@Observes FailedExecution event) {
        if (!TRIGGER_ID.equals(event.getExecution().getTrigger().getId())) {
            return;
        }

        Log.errorf(event.getException(), "Retentiejob mislukt (trigger=%s) — mogelijk niet alle "
                + "verlopen notificaties verwijderd", event.getExecution().getTrigger().getId());
    }
}
