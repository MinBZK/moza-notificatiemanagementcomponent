package nl.rijksoverheid.moz.nmc.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.Poging_;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class PogingRepository implements PanacheRepositoryBase<Poging, UUID> {

    public Optional<Poging> findByNotifyId(UUID notifyId) {
        return find(Poging_.NOTIFY_ID, notifyId).singleResultOptional();
    }

    /** De poging met het hoogste nummer van een notificatie: de lopende of laatst afgeronde verzending. */
    public Optional<Poging> findLaatsteVan(UUID notificatieId) {
        return find(Poging_.NOTIFICATIE_ID + " = ?1", Sort.descending(Poging_.NUMMER), notificatieId).firstResultOptional();
    }

    /** Leest de poging opnieuw uit de database, voorbij wat de persistence context al had. */
    public void herlaad(Poging poging) {
        getEntityManager().refresh(poging);
    }
}
