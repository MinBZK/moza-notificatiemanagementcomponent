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
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;

import java.util.Objects;
import java.util.UUID;

/**
 * De enige schrijver van de notificatiestatus. Elke overgang vergrendelt de notificatierij, toetst
 * het paar aan {@link Overgangsregels}, verhoogt de versie met precies één en schrijft een event met
 * die versie als volgnummer, in de transactie van de aanroeper.
 * <p>
 * Elke overgang naar een terminale status plant de wistaak op de wistermijn vanaf die overgang, of verzet
 * een open wistaak daarheen. Zo gaat geen schrijver van een terminale status aan het wissen voorbij, en
 * wist een correctie van {@code BEZORGSTATUS_ONBEKEND} niet op de termijn van de eerdere status. Een
 * overgang uit een terminale status terug naar een niet-terminale verwijdert de open wistaak.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class Overgangsfunctie {

    private final EntityManager entityManager;
    private final EventRepository eventRepository;
    private final TaakRepository taakRepository;
    private final Bewaartermijnen bewaartermijnen;

    public Overgangsfunctie(EntityManager entityManager, EventRepository eventRepository,
                            TaakRepository taakRepository, Bewaartermijnen bewaartermijnen) {
        this.entityManager = entityManager;
        this.eventRepository = eventRepository;
        this.taakRepository = taakRepository;
        this.bewaartermijnen = bewaartermijnen;
    }

    /** Slaat een nieuwe notificatie op als {@code AANGENOMEN}, met het event met volgnummer 0. */
    public Event neemAan(Notificatie notificatie) {
        Objects.requireNonNull(notificatie, "notificatie is verplicht");

        if (notificatie.getStatus() != null) {
            throw new IllegalStateException("Notificatie " + notificatie.getId() + " is al aangenomen");
        }

        notificatie.pasOvergangToe(NotificatieStatus.AANGENOMEN, null);
        entityManager.persist(notificatie);
        entityManager.flush();

        return schrijfEvent(notificatie, null);
    }

    /**
     * Voert de overgang naar {@code naar} uit als die vanuit de huidige status is toegestaan. Een
     * niet-toegestane overgang wijzigt niets en levert een geweigerde uitkomst op.
     *
     * @throws NotificatieNietGevondenException als de notificatie niet bestaat
     */
    public OvergangUitkomst voerUit(UUID notificatieId, NotificatieStatus naar, Reden reden) {
        Objects.requireNonNull(naar, "naar is verplicht");

        Notificatie notificatie = vergrendel(notificatieId);
        NotificatieStatus van = notificatie.getStatus();

        if (!Overgangsregels.isToegestaan(van, naar)) {
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

        if (naar.isTerminaal()) {
            taakRepository.planWistaak(notificatie.getDvId(), notificatie.getId(),
                    bewaartermijnen.wissenOp(notificatie.getTerminaalOp()));
        } else if (van.isTerminaal()) {
            taakRepository.verwijderOpen(TaakSoort.WISSEN, notificatie.getId());
        }

        return OvergangUitkomst.uitgevoerd(van, schrijfEvent(notificatie, van));
    }

    /**
     * Vergrendelt de notificatierij tot het einde van de transactie. Wie pogingen van de notificatie
     * leest om over een overgang te beslissen, doet dat pas hierna.
     *
     * @throws NotificatieNietGevondenException als de notificatie niet bestaat
     */
    public Notificatie vergrendel(UUID notificatieId) {
        Notificatie notificatie = entityManager.find(Notificatie.class, notificatieId, LockModeType.PESSIMISTIC_WRITE);

        if (notificatie == null) {
            throw new NotificatieNietGevondenException("Geen notificatie gevonden met id " + notificatieId);
        }

        return notificatie;
    }

    // Status en reden komen van de rij, zodat het event nooit afwijkt van wat er vastligt; bij een
    // herverzending wordt de reden van de aanroeper niet op de rij gezet.
    private Event schrijfEvent(Notificatie notificatie, NotificatieStatus van) {
        Event event = new Event(notificatie.getDvId(), notificatie.getId(), notificatie.getVersie(), van,
                notificatie.getStatus(), notificatie.getReden());
        eventRepository.persist(event);

        return event;
    }
}
