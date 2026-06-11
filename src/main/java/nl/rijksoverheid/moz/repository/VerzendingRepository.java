package nl.rijksoverheid.moz.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.entity.Verzending;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class VerzendingRepository implements PanacheRepositoryBase<Verzending, UUID> {

    public Optional<Verzending> findByNotifyReferentie(UUID notifyReferentie) {
        return find("notifyReferentie", notifyReferentie).firstResultOptional();
    }
}
