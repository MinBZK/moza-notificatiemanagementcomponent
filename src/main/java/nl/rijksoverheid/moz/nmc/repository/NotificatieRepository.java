package nl.rijksoverheid.moz.nmc.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class NotificatieRepository implements PanacheRepositoryBase<Notificatie, UUID> {

    public Optional<Notificatie> findByNotifyNlNotificatieId(UUID notifyNlNotificatieId) {
        return find("notifyNlNotificatieId", notifyNlNotificatieId).firstResultOptional();
    }
}
