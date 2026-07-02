package nl.rijksoverheid.moz.nmc.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.Notificatie_;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class NotificatieRepository implements PanacheRepositoryBase<Notificatie, UUID> {

    public Optional<Notificatie> findByExternalReference(UUID externalReference) {
        return find(Notificatie_.EXTERNAL_REFERENCE, externalReference).singleResultOptional();
    }
}
