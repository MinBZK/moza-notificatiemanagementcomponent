package nl.rijksoverheid.moz.nmc.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import nl.rijksoverheid.moz.nmc.domain.VerzendbudgetTijdvak;
import nl.rijksoverheid.moz.nmc.domain.VerzendbudgetTijdvak_;

import java.time.OffsetDateTime;

@ApplicationScoped
public class VerzendbudgetRepository implements PanacheRepositoryBase<VerzendbudgetTijdvak, VerzendbudgetTijdvak.Sleutel> {

    /**
     * Vergrendelt de rij van dit tijdvak tot het einde van de transactie, en maakt hem eerst aan met
     * {@code tokens}, waarvan {@code verzendTokens} gereserveerd voor het verzenden en {@code navraagTokens}
     * voor de navraag, als hij er nog niet is. Een gelijktijdige claim wacht op die insert en leest daarna
     * de rij met wat de eerste er al uit nam.
     */
    public VerzendbudgetTijdvak vergrendel(String notifyService, OffsetDateTime tijdvak, int tokens, int verzendTokens,
                                           int navraagTokens) {
        getSession().createMutationQuery("insert into VerzendbudgetTijdvak ("
                        + String.join(", ", VerzendbudgetTijdvak_.NOTIFY_SERVICE, VerzendbudgetTijdvak_.TIJDVAK,
                                VerzendbudgetTijdvak_.TOKENS, VerzendbudgetTijdvak_.VERZEND_TOKENS,
                                VerzendbudgetTijdvak_.NAVRAAG_TOKENS)
                        + ") values (:notifyService, :tijdvak, :tokens, :verzendTokens, :navraagTokens) on conflict do nothing")
                .setParameter("notifyService", notifyService)
                .setParameter("tijdvak", tijdvak)
                .setParameter("tokens", tokens)
                .setParameter("verzendTokens", verzendTokens)
                .setParameter("navraagTokens", navraagTokens)
                .executeUpdate();

        return findById(new VerzendbudgetTijdvak.Sleutel(notifyService, tijdvak), LockModeType.PESSIMISTIC_WRITE);
    }

    /** Verwijdert de rijen van tijdvakken vóór {@code grens} en geeft het aantal terug. */
    public int verwijderTijdvakkenOuderDan(OffsetDateTime grens) {
        return (int) delete(VerzendbudgetTijdvak_.TIJDVAK + " < ?1", grens);
    }
}
