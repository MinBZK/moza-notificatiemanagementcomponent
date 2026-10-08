package nl.rijksoverheid.moz.nmc.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.domain.TaakStatus;
import org.hibernate.query.NativeQuery;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Toegang tot de takentabel. Elke query noemt de soort, zodat PostgreSQL naar één partitie kan, en
 * elke wijziging van een geclaimde taak toetst het claim-epoch: nul geraakte rijen betekent dat een
 * andere worker de taak inmiddels heeft geclaimd.
 */
@ApplicationScoped
public class TaakRepository implements PanacheRepositoryBase<Taak, Long> {

    // Claim en lease in één statement: de subquery kiest met SKIP LOCKED de rijen die geen andere
    // worker vasthoudt, de UPDATE zet de lease en verhoogt het epoch, en de CTE maakt er een SELECT
    // van zodat Hibernate de rijen als entities leest. %s is de dv-voorwaarde: een gelijkheid, of
    // IS NULL voor taken per systeem.
    private static final String CLAIM_SQL = """
            WITH geclaimd AS (
                UPDATE taak
                   SET lease_tot = ?3, claim_epoch = claim_epoch + 1
                 WHERE (soort, id) IN (
                        SELECT soort, id
                          FROM taak
                         WHERE soort = ?1
                           AND %s
                           AND status = 'OPEN'
                           AND due <= ?2
                           AND (lease_tot IS NULL OR lease_tot < ?2)
                         ORDER BY due
                         FETCH FIRST ?4 ROWS ONLY
                           FOR UPDATE SKIP LOCKED)
             RETURNING *)
            SELECT * FROM geclaimd ORDER BY due
            """;

    // Wie het langst wacht eerst: is het budget kleiner dan het aantal dienstverleners, dan krijgen
    // niet steeds dezelfde dienstverleners niets.
    private static final String DIENSTVERLENERS_MET_WERK_SQL = """
            SELECT dv_id
              FROM taak
             WHERE soort = ?1
               AND status = 'OPEN'
               AND due <= ?2
               AND (lease_tot IS NULL OR lease_tot < ?2)
             GROUP BY dv_id
             ORDER BY min(due)
            """;

    /**
     * De dienstverleners met een taak van deze soort die aan de beurt is; {@code null} in de lijst
     * staat voor taken per systeem.
     */
    @SuppressWarnings("unchecked")
    public List<UUID> dienstverlenersMetOpenWerk(TaakSoort soort, OffsetDateTime nu) {
        return getEntityManager()
                .createNativeQuery(DIENSTVERLENERS_MET_WERK_SQL)
                .unwrap(NativeQuery.class)
                .addScalar("dv_id", UUID.class)
                .setParameter(1, soort.name())
                .setParameter(2, nu)
                .getResultList();
    }

    /**
     * Claimt tot {@code maximum} taken van deze soort en dienstverlener die aan de beurt zijn en niet
     * door een andere worker vastgehouden worden, en zet er een lease op tot {@code leaseTot}.
     *
     * @param dvId de dienstverlener, of {@code null} voor taken per systeem
     */
    @SuppressWarnings("unchecked")
    public List<Taak> claim(TaakSoort soort, UUID dvId, int maximum, OffsetDateTime nu, OffsetDateTime leaseTot) {
        String dvVoorwaarde = dvId == null ? "dv_id IS NULL" : "dv_id = ?5";
        var query = getEntityManager()
                .createNativeQuery(CLAIM_SQL.formatted(dvVoorwaarde), Taak.class)
                .setParameter(1, soort.name())
                .setParameter(2, nu)
                .setParameter(3, leaseTot)
                .setParameter(4, maximum);

        if (dvId != null) {
            query.setParameter(5, dvId);
        }

        return query.getResultList();
    }

    /** Verlengt de lease; false als de taak inmiddels door een andere worker is geclaimd. */
    public boolean verlengLease(Taak taak, OffsetDateTime tot) {
        return wijzig("UPDATE taak SET lease_tot = ?4 WHERE soort = ?1 AND id = ?2 AND claim_epoch = ?3", taak, tot);
    }

    /** Verwijdert de afgeronde taak; false als de taak inmiddels door een andere worker is geclaimd. */
    public boolean rondAf(Taak taak) {
        return wijzig("DELETE FROM taak WHERE soort = ?1 AND id = ?2 AND claim_epoch = ?3", taak);
    }

    /**
     * Geeft de lease vrij en plant de taak opnieuw op {@code due}; false als de taak inmiddels door
     * een andere worker is geclaimd.
     *
     * @param teltAlsPoging of dit uitstel een mislukking in het NMC zelf was
     */
    public boolean stelUit(Taak taak, OffsetDateTime due, boolean teltAlsPoging) {
        return wijzig("UPDATE taak SET lease_tot = NULL, due = ?4, pogingen = pogingen + ?5 "
                + "WHERE soort = ?1 AND id = ?2 AND claim_epoch = ?3", taak, due, teltAlsPoging ? 1 : 0);
    }

    /** Zet de taak op mislukt en geeft de lease vrij; false als een andere worker hem inmiddels claimde. */
    public boolean markeerMislukt(Taak taak) {
        return wijzig("UPDATE taak SET lease_tot = NULL, status = 'MISLUKT' "
                + "WHERE soort = ?1 AND id = ?2 AND claim_epoch = ?3", taak);
    }

    public long telMetStatus(TaakSoort soort, TaakStatus status) {
        return count("soort = ?1 and status = ?2", soort, status);
    }

    /** Het aantal open taken van deze soort dat aan de beurt is: de achterstand. */
    public long telAchterstand(TaakSoort soort, OffsetDateTime nu) {
        return count("soort = ?1 and status = ?2 and due <= ?3", soort, TaakStatus.OPEN, nu);
    }

    public Optional<Taak> zoek(TaakSoort soort, long id) {
        return find("soort = ?1 and id = ?2", soort, id).singleResultOptional();
    }

    private boolean wijzig(String sql, Taak taak, Object... extra) {
        var query = getEntityManager().createNativeQuery(sql)
                .setParameter(1, taak.getSoort().name())
                .setParameter(2, taak.getId())
                .setParameter(3, taak.getClaimEpoch());

        for (int i = 0; i < extra.length; i++) {
            query.setParameter(4 + i, extra[i]);
        }

        return query.executeUpdate() == 1;
    }
}
