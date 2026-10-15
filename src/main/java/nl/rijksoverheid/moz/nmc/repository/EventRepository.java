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
        return getSession().createSelectionQuery("from Event where " + Event_.DV_ID + " = :dvId and ("
                        + Event_.TRANSACTIE_ID + ", " + Event_.ID + ") > (:transactieId, :eventId) and " + Event_.TRANSACTIE_ID
                        + " < function('pg_snapshot_xmin', function('pg_current_snapshot')) order by "
                        + Event_.TRANSACTIE_ID + ", " + Event_.ID, Event.class)
                .setParameter("dvId", dvId)
                .setParameter("transactieId", transactieId, Event_.transactieId.getType())
                .setParameter("eventId", eventId)
                .setMaxResults(maximum)
                .getResultList();
    }

    /** De transactie-id van het oudste event in het log, over alle dienstverleners; leeg zonder events. */
    public Optional<Long> oudsteTransactieId() {
        return getEntityManager().createQuery("select " + Event_.TRANSACTIE_ID + " from Event order by " + Event_.TRANSACTIE_ID, Long.class)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }
}
