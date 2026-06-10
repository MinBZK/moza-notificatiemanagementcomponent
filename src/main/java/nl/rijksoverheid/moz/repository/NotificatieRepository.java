package nl.rijksoverheid.moz.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.entity.Notificatie;

import java.util.UUID;

@ApplicationScoped
public class NotificatieRepository implements PanacheRepositoryBase<Notificatie, UUID> {
}
