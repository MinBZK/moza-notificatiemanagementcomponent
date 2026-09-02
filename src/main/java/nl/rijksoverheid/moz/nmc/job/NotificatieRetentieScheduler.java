package nl.rijksoverheid.moz.nmc.job;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
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
import java.util.List;
import java.util.UUID;

/**
 * Ruimt Notificatie-rijen op waarvan de status langer dan de bewaartermijn niet is bijgewerkt.
 * Losgekoppeld van het afleveren van statussen aan de Dienstverlener (zowel via callback als een
 * eventuele toekomstige GET): alleen een binnenkomende NotifyNL-statusupdate telt mee. De
 * bewaartermijn geldt gelijk voor elke status; StatusWaarde#isDefinitief bepaalt hier alleen op
 * welk niveau een verwijdering gelogd wordt (zie verwijderVerlopenNotificaties).
 */
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
    // tegelijk vuren botsen niet fataal — de tragere pod loopt tegen rijlocks aan, verwijdert 0 rijen
    // in die batch en stopt zijn lus, en eventuele restachterstand wordt bij de volgende run alsnog
    // opgeruimd. Geen @Transactional op deze methode zelf: elke batch draait in zijn eigen
    // transactie, zodat al verwijderde batches niet worden teruggedraaid als een latere batch faalt.
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
        } while (resultaat.totaal() == BATCH_GROOTTE);

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
    // in plaats van native SQL. De status wordt in dezelfde SELECT meegelezen, zodat de
    // definitief/niet-definitief-telling voor de WARN-log geen aparte (en dus potentieel
    // inconsistente) query nodig heeft — de classificatie gebeurt op precies de rijen die ook echt
    // verwijderd worden.
    private BatchResultaat verwijderBatch(OffsetDateTime grens) {
        List<Object[]> kandidaten = notificatieRepository.getEntityManager()
                .createQuery("SELECT n.id, n." + Notificatie_.STATUS + " FROM Notificatie n WHERE n."
                        + Notificatie_.LAATSTE_STATUS_UPDATE + " <= :grens ORDER BY n."
                        + Notificatie_.LAATSTE_STATUS_UPDATE, Object[].class)
                .setParameter("grens", grens)
                .setMaxResults(BATCH_GROOTTE)
                .getResultList();

        if (kandidaten.isEmpty()) {
            return new BatchResultaat(0, 0);
        }

        List<UUID> ids = kandidaten.stream().map(rij -> (UUID) rij[0]).toList();
        long nietDefinitiefAantal = kandidaten.stream()
                .map(rij -> (StatusWaarde) rij[1])
                .filter(status -> !status.isDefinitief())
                .count();

        int verwijderd = (int) notificatieRepository.delete("id IN ?1", ids);
        return new BatchResultaat(verwijderd, (int) nietDefinitiefAantal);
    }

    record BatchResultaat(int totaal, int nietDefinitiefAantal) {
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
