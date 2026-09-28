package nl.rijksoverheid.moz.nmc.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.Event_;
import org.hibernate.query.NativeQuery;
import org.hibernate.type.StandardBasicTypes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class EventRepository implements PanacheRepositoryBase<Event, Long> {

    // Native SQL: de kolom xid (xid8) is niet gemapt en het watermerk is een PostgreSQL-functie.
    // Alleen events onder de oudste lopende transactie tellen mee, zodat een cursor nooit een
    // event overslaat dat later committet dan een event met een hogere xid. xid8 kent geen cast
    // van of naar bigint; die loopt via text.
    private static final String FEED_SQL = """
            SELECT e.*, e.xid::text::bigint AS xid_nr
              FROM event e
             WHERE e.dv_id = ?1
               AND (e.xid, e.id) > (CAST(CAST(?2 AS text) AS xid8), ?3)
               AND e.xid < pg_snapshot_xmin(pg_current_snapshot())
             ORDER BY e.xid, e.id
             FETCH FIRST ?4 ROWS ONLY
            """;

    private static final String OUDSTE_XID_SQL = """
            SELECT xid::text::bigint AS xid_nr
              FROM event
             ORDER BY xid
             FETCH FIRST 1 ROWS ONLY
            """;

    public List<Event> findByNotificatie(UUID notificatieId) {
        return list(Event_.NOTIFICATIE_ID, Sort.by(Event_.VOLGNUMMER), notificatieId);
    }

    /**
     * De events van deze dienstverlener ná de positie {@code (xid, eventId)}, in commitvolgorde, tot
     * {@code maximum} stuks. Met {@code (0, 0)} begint het lezen bij het oudste beschikbare event.
     */
    @SuppressWarnings("unchecked")
    public List<EventMetXid> leesVanafPositie(UUID dvId, long xid, long eventId, int maximum) {
        List<Object[]> rijen = getEntityManager()
                .createNativeQuery(FEED_SQL)
                .unwrap(NativeQuery.class)
                .addEntity("e", Event.class)
                .addScalar("xid_nr", StandardBasicTypes.LONG)
                .setParameter(1, dvId)
                .setParameter(2, xid)
                .setParameter(3, eventId)
                .setParameter(4, maximum)
                .getResultList();

        return rijen.stream().map(rij -> new EventMetXid((Long) rij[1], (Event) rij[0])).toList();
    }

    /** De transactie-id van het oudste event in het log, over alle dienstverleners; leeg zonder events. */
    @SuppressWarnings("unchecked")
    public Optional<Long> oudsteXid() {
        List<Long> rijen = getEntityManager()
                .createNativeQuery(OUDSTE_XID_SQL)
                .unwrap(NativeQuery.class)
                .addScalar("xid_nr", StandardBasicTypes.LONG)
                .getResultList();

        return rijen.stream().findFirst();
    }
}
