package nl.rijksoverheid.moz.nmc.job;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.FailedExecution;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.SkippedExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import nl.rijksoverheid.moz.nmc.domain.Notificatie_;
import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

/**
 * Ruimt Notificatie-rijen op waarvan de status langer dan de bewaartermijn niet is bijgewerkt.
 * Losgekoppeld van het afleveren van statussen aan de Dienstverlener (zowel via callback als een
 * eventuele toekomstige GET): alleen een binnenkomende NotifyNL-statusupdate telt mee.
 * <p>
 * Alleen notificaties met een definitieve status (StatusWaarde#isDefinitief) worden daadwerkelijk
 * verwijderd. Een notificatie zonder definitieve status — NotifyNL heeft er nog geen (eind)status
 * over teruggekoppeld — wordt bij het verstrijken van de bewaartermijn bewaard en alleen
 * gesignaleerd (WARN-log).
 */
@ApplicationScoped
public class NotificatieRetentieScheduler {

    // Eén keer afgeleid van StatusWaarde#isDefinitief (de hardcoded, door NMC bepaalde bron
    // van waarheid) i.p.v. hier een eigen lijst bij te houden die uit de pas kan lopen.
    private static final List<StatusWaarde> DEFINITIEVE_STATUSSEN = Arrays.stream(StatusWaarde.values())
            .filter(StatusWaarde::isDefinitief)
            .toList();

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
    // Geen Quartz-clustering nodig: een pure bulk-delete op laatsteStatusUpdate is van nature
    // idempotent, dus meerdere pods die tegelijk vuren doen elkaar geen kwaad.
    @Scheduled(cron = "{notificatie.retentie.cron}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    public void verwijderVerlopenNotificaties() {
        OffsetDateTime grens = OffsetDateTime.now(ZoneOffset.UTC).minus(bewaartermijn);

        long verwijderd = notificatieRepository.delete(
                Notificatie_.STATUS + " IN ?1 AND " + Notificatie_.LAATSTE_STATUS_UPDATE + " <= ?2",
                DEFINITIEVE_STATUSSEN, grens);

        long nietDefinitiefVerlopenAantal = notificatieRepository.count(
                Notificatie_.STATUS + " NOT IN ?1 AND " + Notificatie_.LAATSTE_STATUS_UPDATE + " <= ?2",
                DEFINITIEVE_STATUSSEN, grens);

        // Pas loggen nadat beide queries zijn geslaagd: anders zou een falende count()-aanroep een
        // net gelogde "N verwijderd"-regel achterlaten terwijl de transactie (inclusief die
        // verwijdering) bij het committen alsnog wordt teruggedraaid.
        Log.infof("Retentiejob: %d verlopen notificatie(s) met definitieve status verwijderd (grens=%s)",
                verwijderd, grens);

        if (nietDefinitiefVerlopenAantal > 0) {
            OffsetDateTime oudsteLaatsteStatusUpdate = notificatieRepository.getEntityManager()
                    .createQuery("SELECT MIN(n." + Notificatie_.LAATSTE_STATUS_UPDATE + ") FROM Notificatie n "
                            + "WHERE n." + Notificatie_.STATUS + " NOT IN :statussen AND n."
                            + Notificatie_.LAATSTE_STATUS_UPDATE + " <= :grens", OffsetDateTime.class)
                    .setParameter("statussen", DEFINITIEVE_STATUSSEN)
                    .setParameter("grens", grens)
                    .getSingleResult();

            // Kan wijzen op een notificatie waarvoor NotifyNL nooit een statusupdate heeft gestuurd
            // — de moeite van het uitzoeken waard. oudsteLaatsteStatusUpdate geeft meteen een
            // indicatie hoe erg: ver voorbij grens is zorgwekkender dan net erover.
            Log.warnf("Retentiejob: %d notificatie(s) hebben nog geen definitieve status maar zijn al "
                    + "langer dan de bewaartermijn niet bijgewerkt (grens=%s, oudste laatsteStatusUpdate=%s) "
                    + "— vooralsnog bewaard", nietDefinitiefVerlopenAantal, grens, oudsteLaatsteStatusUpdate);
        }
    }

    // Vuurt voor elke @Scheduled-methode in de applicatie; momenteel de enige, dus geen filtering
    // op trigger-id nodig. Zonder deze observer wordt een overgeslagen run alleen op DEBUG gelogd
    // (standaardniveau is INFO): een vastgelopen run (lock-wait, DB-failover) zou de opruiming dan
    // stilletjes voor de rest van de levensduur van de pod stopzetten, zonder enig signaal.
    void opOvergeslagenUitvoering(@Observes SkippedExecution event) {
        Log.warnf("Retentiejob overgeslagen (trigger=%s, reden=%s) — vorige run draait mogelijk nog vast",
                event.getExecution().getTrigger().getId(), event.getDetail());
    }

    // Vangt zowel een exception uit de methode hierboven áls een mislukte commit erna (bijv. een
    // JTA-timeout) — een lokale try/catch in verwijderVerlopenNotificaties zou alleen het eerste
    // geval dekken. Zonder deze observer belandt zo'n fout alleen onder Quarkus' eigen
    // schedulerlogcategorie, niet onder dit package, en zou een operator die op
    // nl.rijksoverheid.moz.* filtert een structureel mislukkende opruimrun nooit opmerken.
    void opMislukteUitvoering(@Observes FailedExecution event) {
        Log.errorf(event.getException(), "Retentiejob mislukt (trigger=%s) — transactie teruggedraaid, "
                + "er zijn geen notificaties verwijderd", event.getExecution().getTrigger().getId());
    }
}
