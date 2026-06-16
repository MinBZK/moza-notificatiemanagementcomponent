package nl.rijksoverheid.moz.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.domain.Notificatie;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class NotificatieRepository implements PanacheRepository<Notificatie> {

    public Optional<Notificatie> findByNotifyNlNotificatieId(UUID notifyNlNotificatieId) {
        return find("notifyNlNotificatieId", notifyNlNotificatieId).firstResultOptional();
    }
}
