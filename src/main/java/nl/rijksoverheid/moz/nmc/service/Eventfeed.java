package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import nl.rijksoverheid.moz.nmc.domain.Bevestiging;
import nl.rijksoverheid.moz.nmc.domain.Cursor;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.repository.BevestigingRepository;
import nl.rijksoverheid.moz.nmc.repository.EventMetXid;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Leest het eventlog per dienstverlener op (transactie-id, event-id) onder het watermerk van de
 * oudste lopende transactie, en bewaart de cursor die de Dienstverlener terugschrijft.
 */
@ApplicationScoped
public class Eventfeed {

    private final EventRepository eventRepository;
    private final BevestigingRepository bevestigingRepository;
    private final Clock klok;
    private final int epoch;
    private final int maxPagina;

    public Eventfeed(EventRepository eventRepository, BevestigingRepository bevestigingRepository, Clock klok,
                     @ConfigProperty(name = "nmc.feed.cluster-epoch") int epoch,
                     @ConfigProperty(name = "nmc.feed.max-pagina") int maxPagina) {
        if (epoch < 0 || maxPagina < 1) {
            throw new IllegalStateException("nmc.feed.cluster-epoch mag niet negatief zijn en nmc.feed.max-pagina moet 1 of hoger zijn");
        }

        this.eventRepository = eventRepository;
        this.bevestigingRepository = bevestigingRepository;
        this.klok = klok;
        this.epoch = epoch;
        this.maxPagina = maxPagina;
    }

    /**
     * De volgende pagina na {@code cursor}; zonder cursor vanaf het oudste beschikbare event.
     *
     * @param limiet het gewenste aantal events, of null voor het maximum; meer dan het maximum levert
     *               het maximum
     * @throws CursorVervallenException als de cursor uit een ander epoch komt of onder het oudste
     *                                  beschikbare event wijst
     */
    @Transactional
    public EventPagina lees(UUID dvId, Cursor cursor, Integer limiet) {
        if (limiet != null && limiet < 1) {
            throw new IllegalArgumentException("limiet moet 1 of hoger zijn, is " + limiet);
        }

        valideer(cursor);

        return leesVanaf(dvId, cursor, limiet == null ? maxPagina : Math.min(limiet, maxPagina));
    }

    /**
     * De volgende pagina na de leverpositie van de webhook, onder hetzelfde watermerk als de feed.
     * Een positie uit een ander epoch begint opnieuw bij het oudste beschikbare event; een positie
     * onder het oudste event leest gewoon verder, want wat daaronder lag is al opgeruimd.
     *
     * @param positie het laatst geleverde event, of null als er nog niets is geleverd
     */
    @Transactional
    public EventPagina leesVoorWebhook(UUID dvId, Cursor positie, int limiet) {
        if (positie != null && positie.epoch() != epoch) {
            Log.warnf("Webhookpositie van dienstverlener %s komt uit cluster-epoch %d; het huidige is %d. "
                    + "De levering begint opnieuw bij het oudste beschikbare event", dvId, positie.epoch(), epoch);

            return leesVanaf(dvId, null, Math.min(limiet, maxPagina));
        }

        return leesVanaf(dvId, positie, Math.min(limiet, maxPagina));
    }

    private EventPagina leesVanaf(UUID dvId, Cursor cursor, int grootte) {
        long xid = cursor == null ? 0 : cursor.xid();
        long eventId = cursor == null ? 0 : cursor.eventId();
        List<EventMetXid> regels = eventRepository.leesVanafPositie(dvId, xid, eventId, grootte);

        if (regels.isEmpty()) {
            return new EventPagina(List.of(), cursor);
        }

        EventMetXid laatste = regels.getLast();
        List<Event> events = regels.stream().map(EventMetXid::event).toList();

        return new EventPagina(events, new Cursor(epoch, laatste.xid(), laatste.event().getId()));
    }

    /**
     * Legt de cursor vast als bevestiging; een cursor die niet verder ligt dan de bewaarde verandert
     * niets.
     *
     * @return of de bevestiging is gewijzigd
     * @throws CursorVervallenException als de cursor uit een ander epoch komt of onder het oudste
     *                                  beschikbare event wijst
     */
    @Transactional
    public boolean bevestig(UUID dvId, Cursor cursor) {
        Objects.requireNonNull(cursor, "cursor is verplicht");
        valideer(cursor);

        OffsetDateTime nu = OffsetDateTime.now(klok).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

        return bevestigingRepository.zetVooruit(dvId, cursor, nu);
    }

    @Transactional
    public Optional<Bevestiging> bevestiging(UUID dvId) {
        return bevestigingRepository.zoek(dvId);
    }

    // Het log wordt per partitie opgeruimd, dus alles onder het oudste event is weg; een cursor
    // daaronder kan events gemist hebben.
    private void valideer(Cursor cursor) {
        if (cursor == null) {
            return;
        }

        if (cursor.epoch() != epoch) {
            throw new CursorVervallenException("De cursor komt uit cluster-epoch " + cursor.epoch()
                    + "; het huidige epoch is " + epoch);
        }

        Optional<Long> oudste = eventRepository.oudsteXid();

        if (oudste.isPresent() && cursor.xid() < oudste.get()) {
            throw new CursorVervallenException("De cursor wijst onder het oudste beschikbare event");
        }
    }
}
