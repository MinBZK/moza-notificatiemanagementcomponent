package nl.rijksoverheid.moz.nmc.service.feed;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import nl.rijksoverheid.moz.nmc.config.FeedConfig;
import nl.rijksoverheid.moz.nmc.domain.Bevestiging;
import nl.rijksoverheid.moz.nmc.domain.Cursor;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.repository.BevestigingRepository;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;

/**
 * Leest het eventlog per dienstverlener op (transactie-id, event-id) onder het watermerk van de
 * oudste lopende transactie, en bewaart de cursor die de Dienstverlener terugschrijft.
 */
@ApplicationScoped
public class Eventfeed {

    private final EventRepository eventRepository;
    private final BevestigingRepository bevestigingRepository;
    private final Clock clock;
    private final int epoch;
    private final int maxPagina;
    private final String source;

    public Eventfeed(
            EventRepository eventRepository,
            BevestigingRepository bevestigingRepository,
            Clock clock,
            FeedConfig config) {
        this.eventRepository = eventRepository;
        this.bevestigingRepository = bevestigingRepository;
        this.clock = clock;
        this.epoch = config.clusterEpoch();
        this.maxPagina = config.maxPagina();
        this.source = config.source();
    }

    /** De {@code source} van de CloudEvents die dit NMC uitgeeft. */
    public String source() {
        return source;
    }

    /**
     * De volgende pagina na {@code cursor}; zonder cursor vanaf het oudste beschikbare event.
     *
     * @param dvId   de dienstverlener wiens events gelezen worden
     * @param cursor het laatst gelezen event, of null om bij het oudste beschikbare event te beginnen
     * @param limiet het gewenste aantal events, of null voor het maximum; meer dan het maximum levert
     *               het maximum
     * @throws CursorVervallenException als de cursor uit een ander epoch komt, onder het oudste
     *                                  beschikbare event wijst of boven de jongste transactie
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
     * onder het oudste event leest gewoon verder met een waarschuwing, want wat daartussen lag is
     * opgeruimd en bereikt de webhook niet.
     *
     * @param dvId    de dienstverlener van de webhook
     * @param positie het laatst geleverde event, of null om bij het oudste beschikbare event te beginnen
     * @param limiet  het grootste aantal events in de pagina
     */
    @Transactional
    public EventPagina leesVoorWebhook(UUID dvId, Cursor positie, int limiet) {
        if (positie != null && positie.epoch() != epoch) {
            Log.warnf(
                    "Webhookpositie van dienstverlener %s komt uit cluster-epoch %d; het huidige is %d. "
                            + "De levering begint opnieuw bij het oudste beschikbare event",
                    dvId, positie.epoch(), epoch);

            return leesVanaf(dvId, null, Math.min(limiet, maxPagina));
        }

        if (positie != null
                && eventRepository
                        .oudsteTransactieId()
                        .filter(oudste -> positie.transactieId() < oudste)
                        .isPresent()) {
            Log.warnf(
                    "Webhookpositie van dienstverlener %s ligt onder het oudste beschikbare event; wat daartussen is "
                            + "opgeruimd bereikt de webhook niet",
                    dvId);
        }

        return leesVanaf(dvId, positie, Math.min(limiet, maxPagina));
    }

    /**
     * De positie van het jongste event onder het watermerk, over alle dienstverleners; het begin van
     * het log als er nog geen event is. Een webhook die zich registreert begint hier, zodat hij geen
     * oudere events krijgt.
     */
    @Transactional
    public Cursor kopVanHetLog() {
        return eventRepository
                .jongsteOnderWatermerk()
                .map(event -> new Cursor(epoch, event.getTransactieId(), event.getId()))
                .orElseGet(() -> new Cursor(epoch, 0, 0));
    }

    private EventPagina leesVanaf(UUID dvId, Cursor cursor, int grootte) {
        long transactieId = cursor == null ? 0 : cursor.transactieId();
        long eventId = cursor == null ? 0 : cursor.eventId();
        List<Event> events = eventRepository.leesVanafPositie(dvId, transactieId, eventId, grootte);

        if (events.isEmpty()) {
            return new EventPagina(List.of(), cursor);
        }

        Event laatste = events.getLast();

        return new EventPagina(events, new Cursor(epoch, laatste.getTransactieId(), laatste.getId()));
    }

    /**
     * Legt de cursor vast als bevestiging; een cursor die niet verder ligt dan de bewaarde verandert niets.
     * Dezelfde cursor opnieuw ververst het tijdstip dus niet: een dienstverlener die blijft hangen op een
     * oude cursor houdt het eventlog niet langer vast dan de maximale cursorleeftijd.
     *
     * @return of de bevestiging is gewijzigd
     * @throws CursorVervallenException als de cursor uit een ander epoch komt, onder het oudste
     *                                  beschikbare event wijst of boven de jongste transactie
     */
    @Transactional
    public boolean bevestig(UUID dvId, Cursor cursor) {
        Objects.requireNonNull(cursor, "cursor is verplicht");
        valideer(cursor);

        OffsetDateTime nu =
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

        return bevestigingRepository.updateCursorPositie(dvId, cursor, nu);
    }

    @Transactional
    public Optional<Bevestiging> bevestiging(UUID dvId) {
        return bevestigingRepository.findByIdOptional(dvId);
    }

    // Het log wordt per partitie opgeruimd, dus alles onder het oudste event is weg; een cursor
    // daaronder kan events gemist hebben. Een cursor boven de jongste transactie is door dit cluster
    // niet uitgegeven (een herstel uit back-up zonder verhoogd epoch).
    private void valideer(Cursor cursor) {
        if (cursor == null) {
            return;
        }

        if (cursor.epoch() != epoch) {
            throw new CursorVervallenException(
                    "De cursor komt uit cluster-epoch " + cursor.epoch() + "; het huidige epoch is " + epoch);
        }

        Optional<Long> oudste = eventRepository.oudsteTransactieId();

        if (oudste.isPresent() && cursor.transactieId() < oudste.get()) {
            throw new CursorVervallenException("De cursor wijst onder het oudste beschikbare event");
        }

        if (cursor.transactieId() >= eventRepository.bovengrensTransactieId()) {
            throw new CursorVervallenException("De cursor wijst boven de jongste transactie van dit cluster");
        }
    }
}
