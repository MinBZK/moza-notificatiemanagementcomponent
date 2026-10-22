package nl.rijksoverheid.moz.nmc.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.Event_;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class EventRepository implements PanacheRepositoryBase<Event, Long> {

    public List<Event> findByNotificatie(UUID notificatieId) {
        return list(Event_.NOTIFICATIE_ID, Sort.by(Event_.VOLGNUMMER), notificatieId);
    }

    /**
     * De events van deze dienstverlener ná de positie {@code (transactieId, eventId)}, op volgorde van transactie-id, tot
     * {@code maximum} stuks. Met {@code (0, 0)} begint het lezen bij het oudste beschikbare event. Alleen
     * events onder de oudste lopende transactie tellen mee, zodat een cursor nooit een event overslaat dat
     * later committet dan een event met een hogere transactie-id.
     */
    public List<Event> leesVanafPositie(UUID dvId, long transactieId, long eventId, int maximum) {
        // In een rijvergelijking leidt Hibernate het parametertype niet af; zonder het type van de
        // kolom gaat de transactie-id als bigint en kent PostgreSQL geen vergelijking met xid8.
        // De losse ondergrens op transactie_id is overbodig voor het resultaat, maar PostgreSQL
        // snoeit partities niet op een rijvergelijking; zonder die grens doorzoekt elke aanroep ook
        // de partities onder de cursor.
        return getSession().createSelectionQuery("from Event where " + Event_.DV_ID + " = :dvId and ("
                        + Event_.TRANSACTIE_ID + ", " + Event_.ID + ") > (:transactieId, :eventId) and "
                        + Event_.TRANSACTIE_ID + " >= :transactieId and " + Event_.TRANSACTIE_ID
                        + " < function('pg_snapshot_xmin', function('pg_current_snapshot')) order by "
                        + Event_.TRANSACTIE_ID + ", " + Event_.ID, Event.class)
                .setParameter("dvId", dvId)
                .setParameter("transactieId", transactieId, Event_.transactieId.getType())
                .setParameter("eventId", eventId)
                .setMaxResults(maximum)
                .getResultList();
    }

    /** Het jongste event onder de oudste lopende transactie, over alle dienstverleners; leeg zonder events. */
    public Optional<Event> jongsteOnderWatermerk() {
        return getSession().createSelectionQuery("from Event where " + Event_.TRANSACTIE_ID
                        + " < function('pg_snapshot_xmin', function('pg_current_snapshot')) order by "
                        + Event_.TRANSACTIE_ID + " desc, " + Event_.ID + " desc", Event.class)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst();
    }

    /**
     * De bovengrens van de huidige snapshot: één hoger dan de jongste toegekende transactie-id. Een
     * cursor op of boven deze grens kan niet door dit cluster zijn uitgegeven. Bewust niet
     * {@code pg_current_xact_id()}, dat kent de lezende transactie zelf een id toe.
     */
    public long bovengrensTransactieId() {
        Object waarde = getEntityManager()
                .createNativeQuery("select pg_snapshot_xmax(pg_current_snapshot())::text::bigint")
                .getSingleResult();

        return ((Number) waarde).longValue();
    }

    /** De transactie-id van het oudste event in het log, over alle dienstverleners; leeg zonder events. */
    public Optional<Long> oudsteTransactieId() {
        return getEntityManager().createQuery("select " + Event_.TRANSACTIE_ID + " from Event order by " + Event_.TRANSACTIE_ID, Long.class)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }
}
