package nl.rijksoverheid.moz.nmc.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.OvergangUitkomst;
import nl.rijksoverheid.moz.nmc.domain.Overgangsregels;
import nl.rijksoverheid.moz.nmc.domain.Reden;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;

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

    private final NotificatieRepository notificatieRepository;
    private final EventRepository eventRepository;

    public Overgangsfunctie(NotificatieRepository notificatieRepository, EventRepository eventRepository) {
        this.notificatieRepository = notificatieRepository;
        this.eventRepository = eventRepository;
    }

    /** Slaat een nieuwe notificatie op als {@code AANGENOMEN}, met het event met volgnummer 0. */
    public Event neemAan(Notificatie notificatie) {
        Objects.requireNonNull(notificatie, "notificatie is verplicht");

        if (notificatie.getStatus() != null) {
            throw new IllegalStateException("Notificatie " + notificatie.getId() + " is al aangenomen");
        }

        notificatie.pasOvergangToe(NotificatieStatus.AANGENOMEN, null);
        notificatieRepository.persistAndFlush(notificatie);

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
            notificatieRepository.verhoogVersie(notificatie);
        } else {
            notificatie.pasOvergangToe(naar, reden);
        }

        notificatieRepository.flush();

        return OvergangUitkomst.uitgevoerd(van, schrijfEvent(notificatie, van));
    }

    /**
     * Vergrendelt de notificatierij tot het einde van de transactie. Wie pogingen van de notificatie
     * leest om over een overgang te beslissen, doet dat pas hierna.
     *
     * @throws NotificatieNietGevondenException als de notificatie niet bestaat
     */
    public Notificatie vergrendel(UUID notificatieId) {
        return notificatieRepository.vergrendel(notificatieId)
                .orElseThrow(() -> new NotificatieNietGevondenException("Geen notificatie gevonden met id " + notificatieId));
    }

    // Status en reden komen van de rij, zodat het event nooit afwijkt van wat er vastligt; bij een
    // herverzending wordt de reden van de aanroeper niet op de rij gezet.
    private Event schrijfEvent(Notificatie notificatie, NotificatieStatus van) {
        Event event = new Event(notificatie.getId(), notificatie.getVersie(), van,
                notificatie.getStatus(), notificatie.getReden());
        eventRepository.persist(event);

        return event;
    }
}
