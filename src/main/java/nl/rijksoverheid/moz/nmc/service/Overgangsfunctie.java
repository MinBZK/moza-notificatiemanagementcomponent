package nl.rijksoverheid.moz.nmc.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.OvergangUitkomst;
import nl.rijksoverheid.moz.nmc.domain.Overgangsregels;
import nl.rijksoverheid.moz.nmc.domain.Reden;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;

import java.util.Objects;
import java.util.UUID;

/**
 * De enige schrijver van de notificatiestatus. Elke overgang vergrendelt de notificatierij, toetst
 * het paar aan {@link Overgangsregels}, verhoogt de versie met precies één en schrijft een event met
 * die versie als volgnummer, in de transactie van de aanroeper.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class Overgangsfunctie {

    private final EntityManager entityManager;
    private final EventRepository eventRepository;

    public Overgangsfunctie(EntityManager entityManager, EventRepository eventRepository) {
        this.entityManager = entityManager;
        this.eventRepository = eventRepository;
    }

    /** Slaat een nieuwe notificatie op als {@code AANGENOMEN}, met het event met volgnummer 0. */
    public Event neemAan(Notificatie notificatie) {
        Objects.requireNonNull(notificatie, "notificatie is verplicht");

        if (notificatie.getNotificatieStatus() != null) {
            throw new IllegalStateException("Notificatie " + notificatie.getId() + " is al aangenomen");
        }

        notificatie.pasOvergangToe(NotificatieStatus.AANGENOMEN, null);
        entityManager.persist(notificatie);
        entityManager.flush();

        return schrijfEvent(notificatie, null, null);
    }

    /**
     * Voert de overgang naar {@code naar} uit als die vanuit de huidige status is toegestaan. Een
     * niet-toegestane overgang wijzigt niets en levert een geweigerde uitkomst op.
     *
     * @throws NotificatieNietGevondenException als de notificatie niet bestaat
     */
    public OvergangUitkomst voerUit(UUID notificatieId, NotificatieStatus naar, Reden reden) {
        Objects.requireNonNull(naar, "naar is verplicht");

        Notificatie notificatie = entityManager.find(Notificatie.class, notificatieId, LockModeType.PESSIMISTIC_WRITE);

        if (notificatie == null) {
            throw new NotificatieNietGevondenException("Geen notificatie gevonden met id " + notificatieId);
        }

        NotificatieStatus van = notificatie.getNotificatieStatus();

        if (van == null || !Overgangsregels.isToegestaan(van, naar)) {
            return OvergangUitkomst.geweigerd(van, naar);
        }

        // Bij een herverzending blijft de status gelijk en is de entity niet gewijzigd; dan verhoogt
        // alleen de force-increment de versie. Anders doet de flush dat, en een tweede ophoging zou
        // een gat in de volgnummers geven.
        if (van == naar) {
            entityManager.lock(notificatie, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        } else {
            notificatie.pasOvergangToe(naar, reden);
        }

        entityManager.flush();

        return OvergangUitkomst.uitgevoerd(van, schrijfEvent(notificatie, van, reden));
    }

    private Event schrijfEvent(Notificatie notificatie, NotificatieStatus van, Reden reden) {
        Event event = new Event(notificatie.getId(), notificatie.getVersie(), van,
                notificatie.getNotificatieStatus(), reden);
        eventRepository.persist(event);

        return event;
    }
}
