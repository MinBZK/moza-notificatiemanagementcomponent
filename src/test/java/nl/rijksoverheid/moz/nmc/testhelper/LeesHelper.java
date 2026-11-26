package nl.rijksoverheid.moz.nmc.testhelper;

import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.domain.Taak_;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;

/**
 * Leest terug wat een test heeft laten opslaan, elk in een eigen transactie, zodat de test de stand
 * in de database ziet en niet een entity uit een eerdere transactie. Alleen in een {@code @QuarkusTest}.
 */
public final class LeesHelper {

    private LeesHelper() {}

    /** De notificatie, of {@code null} als ze niet bestaat. */
    public static Notificatie notificatie(UUID id) {
        return QuarkusTransaction.requiringNew()
                .call(() -> bean(NotificatieRepository.class).findById(id));
    }

    /** De poging met dit id, of {@code null} als ze niet bestaat. */
    public static Poging poging(UUID id) {
        return QuarkusTransaction.requiringNew()
                .call(() -> bean(PogingRepository.class).findById(id));
    }

    /** De poging met het hoogste nummer van de notificatie. */
    public static Poging laatstePoging(UUID notificatieId) {
        return QuarkusTransaction.requiringNew()
                .call(() -> bean(PogingRepository.class)
                        .findLaatsteVan(notificatieId)
                        .orElseThrow());
    }

    /** De statussen waar de notificatie achtereenvolgens naartoe ging, uit het eventlog. */
    public static List<NotificatieStatus> overgangen(UUID notificatieId) {
        return QuarkusTransaction.requiringNew()
                .call(() -> bean(EventRepository.class).findByNotificatie(notificatieId).stream()
                        .map(Event::getNaar)
                        .toList());
    }

    /** Alle taken behalve de wistaak die elke eindstatus krijgt; die staat in WisTaakHandlerTest. */
    public static List<Taak> taken() {
        return QuarkusTransaction.requiringNew()
                .call(() -> bean(TaakRepository.class).list(Taak_.TAAK_SOORT + " != ?1", TaakSoort.WISSEN));
    }

    /** De taken van deze soort. */
    public static List<Taak> taken(TaakSoort taakSoort) {
        return QuarkusTransaction.requiringNew()
                .call(() -> bean(TaakRepository.class).list(Taak_.TAAK_SOORT, taakSoort));
    }

    /** De taak met dit id binnen zijn soort, of leeg als hij er niet (meer) is. */
    public static Optional<Taak> taak(TaakSoort taakSoort, UUID id) {
        return QuarkusTransaction.requiringNew()
                .call(() -> bean(TaakRepository.class).zoek(taakSoort, id));
    }

    /** De ene taak van deze soort; faalt als er geen of meer dan één is. */
    public static Taak enigeTaak(TaakSoort taakSoort) {
        return QuarkusTransaction.requiringNew()
                .call(() -> bean(TaakRepository.class)
                        .find(Taak_.TAAK_SOORT, taakSoort)
                        .singleResult());
    }

    /** De poging die NotifyNL onder dit id kent. */
    public static Poging pogingVanNotifyId(UUID notifyId) {
        return QuarkusTransaction.requiringNew()
                .call(() ->
                        bean(PogingRepository.class).findByNotifyId(notifyId).orElseThrow());
    }

    public static long aantalNotificaties() {
        return QuarkusTransaction.requiringNew()
                .call(() -> bean(NotificatieRepository.class).count());
    }

    public static long aantalPogingen() {
        return QuarkusTransaction.requiringNew()
                .call(() -> bean(PogingRepository.class).count());
    }

    /** Het aantal taken van alle soorten, ook de wistaken. */
    public static long aantalTaken() {
        return QuarkusTransaction.requiringNew()
                .call(() -> bean(TaakRepository.class).count());
    }

    public static long aantalTaken(TaakSoort taakSoort) {
        return QuarkusTransaction.requiringNew()
                .call(() -> bean(TaakRepository.class).count(Taak_.TAAK_SOORT, taakSoort));
    }

    private static <T> T bean(Class<T> type) {
        return Arc.container().instance(type).get();
    }
}
