package nl.rijksoverheid.moz.nmc.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.Event_;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class EventRepository implements PanacheRepositoryBase<Event, Long> {

    public List<Event> findByNotificatie(UUID notificatieId) {
        return list(Event_.NOTIFICATIE_ID, Sort.by(Event_.VOLGNUMMER), notificatieId);
    }
}
