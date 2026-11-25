package nl.rijksoverheid.moz.nmc.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;
import nl.rijksoverheid.moz.nmc.domain.Dienstverlener;

@ApplicationScoped
public class DienstverlenerRepository implements PanacheRepositoryBase<Dienstverlener, UUID> {}
