package nl.rijksoverheid.moz.nmc.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Dienstverlener;
import nl.rijksoverheid.moz.nmc.domain.Dienstverlener_;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class DienstverlenerRepository implements PanacheRepositoryBase<Dienstverlener, UUID> {

    /** De dienstverlener met dit OIN, als hij in het register staat. */
    public Optional<Dienstverlener> zoekOpOin(String oin) {
        return find(Dienstverlener_.OIN, oin).singleResultOptional();
    }
}
