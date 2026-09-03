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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Ruimt Notificatie-rijen op waarvan de status langer dan de bewaartermijn niet is bijgewerkt.
 * Losgekoppeld van het afleveren van statussen aan de Dienstverlener (zowel via callback als een
 * eventuele toekomstige GET): alleen een binnenkomende NotifyNL-statusupdate telt mee. De
 * bewaartermijn geldt gelijk voor elke status; StatusWaarde#isDefinitief bepaalt hier alleen op
 * welk niveau een verwijdering gelogd wordt (zie verwijderVerlopenNotificaties).
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
    // Geen Quartz-clustering nodig: elke batch is een idempotente bulk-delete, dus meerdere pods die
    // tegelijk vuren botsen niet fataal — de tragere pod verwijdert 0 rijen in een batch waar een
    // andere pod al eerst was, en haalt bij de volgende iteratie gewoon een nieuwe kandidatenpagina op
    // (vollePagina hangt af van de queryresultaatpagina, niet van het aantal verwijderde rijen, dus 0
    // verwijderd in een batch stopt de lus niet voortijdig). Geen @Transactional op deze methode zelf:
    // elke batch draait in zijn eigen transactie, zodat al verwijderde batches niet worden
    // teruggedraaid als een latere batch faalt.
    @Scheduled(identity = "notificatie-retentie", cron = "{notificatie.retentie.cron}",
            timeZone = "Europe/Amsterdam", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void verwijderVerlopenNotificaties() {
        OffsetDateTime grens = OffsetDateTime.now(ZoneOffset.UTC).minus(bewaartermijn);

        int totaalVerwijderd = 0;
        int totaalNietDefinitief = 0;
        BatchResultaat resultaat;
        do {
            resultaat = QuarkusTransaction.requiringNew().call(() -> verwijderBatch(grens));
            totaalVerwijderd += resultaat.totaal();
            totaalNietDefinitief += resultaat.nietDefinitiefAantal();
        } while (resultaat.vollePagina());

        Log.infof("Retentiejob: %d verlopen notificatie(s) verwijderd (grens=%s)", totaalVerwijderd, grens);

        if (totaalNietDefinitief > 0) {
            // Kan wijzen op een notificatie waarvoor NotifyNL nooit een (eind)statusupdate heeft
            // gestuurd — de moeite van het uitzoeken waard. De bewaartermijn zelf maakt hier geen
            // onderscheid; dit is puur signalerend.
            Log.warnf("Retentiejob: %d van de %d verwijderde notificaties hadden nog geen definitieve "
                    + "status (grens=%s) — nooit een eindstatus van NotifyNL ontvangen",
                    totaalNietDefinitief, totaalVerwijderd, grens);
        }
    }

    // Selecteert eerst een begrensd aantal kandidaten (JPQL SELECT ... LIMIT is overal ondersteund)
    // en verwijdert die vervolgens via een JPQL bulk-delete op id — zo blijft dit een JPQL-statement
    // (Hibernate ruimt de @ElementCollection-rijen dan zelf op, zie NotificatieRetentieSchedulerTest)
    // in plaats van native SQL. De laatste statusregel per notificatie wordt via een op n
    // gecorreleerde subquery (MAX-tijdstip binnen n.statusGeschiedenis) afgeleid, zodat de
    // definitief/niet-definitief-telling voor de WARN-log op precies dezelfde rijen gebeurt als
    // waarop ook echt verwijderd wordt — geen aparte (en dus potentieel inconsistente) query.
    // externalReference wordt meegelezen zodat een niet-definitieve verwijdering per notificatie
    // gelogd kan worden (zie hieronder) — met alleen het id is zo'n regel niet te herleiden tot een
    // NotifyNL-notificatie om te debuggen.
    private BatchResultaat verwijderBatch(OffsetDateTime grens) {
        List<Object[]> kandidaten = notificatieRepository.getEntityManager()
                .createQuery("SELECT n.id, n." + Notificatie_.EXTERNAL_REFERENCE + ", ns."
                        + NotificatieStatus_.STATUS + " FROM Notificatie n JOIN n." + Notificatie_.STATUS_GESCHIEDENIS
                        + " ns WHERE ns." + NotificatieStatus_.TIJDSTIP + " <= :grens AND ns."
                        + NotificatieStatus_.TIJDSTIP + " = (SELECT MAX(ns2." + NotificatieStatus_.TIJDSTIP
                        + ") FROM n." + Notificatie_.STATUS_GESCHIEDENIS + " ns2) ORDER BY ns."
                        + NotificatieStatus_.TIJDSTIP, Object[].class)
                .setParameter("grens", grens)
                .setMaxResults(BATCH_GROOTTE)
                .getResultList();

        if (kandidaten.isEmpty()) {
            return new BatchResultaat(0, 0, false);
        }

        // De MAX-subquery filtert niet per se op een unieke waarde: heeft een notificatie twee
        // statusregels met hetzelfde tijdstip, dan matcht de subquery ze allebei en levert de JOIN
        // meer dan één rij voor die notificatie op. Dedupliceren op id houdt de telling en de
        // verwijdering hieronder correct ongeacht of dat voorkomt. Bij zo'n gelijkspel telt de
        // notificatie als definitief zodra één van de gelijktijdige statussen dat is — welke van de
        // (eventueel meerdere) definitieve statussen precies bewaard blijft is dan verder een
        // willekeurige keuze, want dat verandert niets aan de definitief/niet-definitief-telling.
        Map<UUID, Kandidaat> laatsteStatusPerNotificatie = kandidaten.stream()
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

        long nietDefinitiefAantal = laatsteStatusPerNotificatie.values().stream()
                .filter(kandidaat -> !kandidaat.status().isDefinitief())
                .count();

        int verwijderd = (int) notificatieRepository.delete("id IN ?1", laatsteStatusPerNotificatie.keySet());
        // vollePagina (niet verwijderd!) bepaalt of de lus doorgaat: de deduplicatie hierboven kan het
        // aantal verwijderde notificaties lager maken dan het aantal opgehaalde kandidaatrijen, terwijl
        // er nog wel kandidaten resteren — de lus moet dus op de queryresultaatpagina varen, niet op
        // het aantal verwijderde rijen.
        boolean vollePagina = kandidaten.size() == BATCH_GROOTTE;
        return new BatchResultaat(verwijderd, (int) nietDefinitiefAantal, vollePagina);
    }

    private record Kandidaat(UUID externalReference, StatusWaarde status) {
    }

    record BatchResultaat(int totaal, int nietDefinitiefAantal, boolean vollePagina) {
    }

    // Vuurt voor elke @Scheduled-methode in de applicatie; momenteel de enige, dus geen filtering
    // op trigger-id nodig. Zonder deze observer wordt een overgeslagen run alleen op DEBUG gelogd
    // (standaardniveau is INFO): een vastgelopen run (lock-wait, DB-failover) zou de opruiming dan
    // stilletjes voor de rest van de levensduur van de pod stopzetten, zonder enig signaal.
    void opOvergeslagenUitvoering(@Observes SkippedExecution event) {
        Log.warnf("Retentiejob overgeslagen (trigger=%s, reden=%s) — vorige run draait mogelijk nog vast",
                event.getExecution().getTrigger().getId(), event.getDetail());
    }

    // Vangt een exception uit de methode hierboven (bijv. uit een van de batches) — zonder deze
    // observer belandt zo'n fout alleen onder Quarkus' eigen schedulerlogcategorie, niet onder dit
    // package, en zou een operator die op nl.rijksoverheid.moz.* filtert een structureel
    // mislukkende opruimrun nooit opmerken. Al vóór de fout gecommitte batches blijven verwijderd.
    void opMislukteUitvoering(@Observes FailedExecution event) {
        Log.errorf(event.getException(), "Retentiejob mislukt (trigger=%s) — mogelijk niet alle "
                + "verlopen notificaties verwijderd", event.getExecution().getTrigger().getId());
    }
}
