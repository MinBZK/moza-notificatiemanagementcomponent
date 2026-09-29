package nl.rijksoverheid.moz.nmc.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Dienstverlener;

import java.util.UUID;

@ApplicationScoped
public class DienstverlenerRepository implements PanacheRepositoryBase<Dienstverlener, UUID> {
}
