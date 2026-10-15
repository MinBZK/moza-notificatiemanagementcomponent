package nl.rijksoverheid.moz.nmc.repository;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import nl.rijksoverheid.moz.nmc.domain.Dienstverlener_;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.domain.TaakStatus;
import nl.rijksoverheid.moz.nmc.domain.Taak_;
import org.hibernate.Timeouts;
import org.hibernate.jpa.SpecHints;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Toegang tot de takentabel. Elke query noemt de soort, zodat PostgreSQL naar één partitie kan, en
 * elke wijziging van een geclaimde taak toetst het claim-epoch: nul geraakte rijen betekent dat een
 * andere worker de taak inmiddels heeft geclaimd.
 */
@ApplicationScoped
public class TaakRepository implements PanacheRepositoryBase<Taak, Long> {

    private static final String AAN_DE_BEURT = Taak_.TAAK_SOORT + " = :taakSoort and " + Taak_.STATUS + " = :open and "
            + Taak_.DUE + " <= :nu and (" + Taak_.LEASE_TOT + " is null or " + Taak_.LEASE_TOT + " < :nu)";

    private static final String ZELFDE_CLAIM = Taak_.TAAK_SOORT + " = :taakSoort and " + Taak_.ID + " = :id and "
            + Taak_.CLAIM_EPOCH + " = :claimEpoch";

    // Native: het conflictdoel is de partiële unieke index op open taken, en het predicaat achter ON
    // CONFLICT moet letterlijk gelijk zijn aan dat van taak_open_per_notificatie_idx. Een latere overgang
    // naar een eindstatus zet de open wistaak op de nieuwe termijn, ook als een worker hem net geclaimd
    // heeft; die leest de termijn opnieuw onder de rijvergrendeling van de notificatie.
    private static final String PLAN_WISTAAK_SQL = """
            INSERT INTO taak (soort, dv_id, notificatie_id, due, status)
            VALUES (?4, ?1, ?2, ?3, ?5)
            ON CONFLICT (notificatie_id, soort)
                  WHERE status = 'OPEN' AND notificatie_id IS NOT NULL AND soort <> 'RECEIPT_VERWERKEN'
            DO UPDATE SET due = EXCLUDED.due
            """;

    /** Plant de wistaak van de notificatie op {@code due}, of verzet de open wistaak daarheen. */
    public void planWistaak(UUID dvId, UUID notificatieId, OffsetDateTime due) {
        getEntityManager().createNativeQuery(PLAN_WISTAAK_SQL)
                .setParameter(1, dvId)
                .setParameter(2, notificatieId)
                .setParameter(3, due)
                .setParameter(4, TaakSoort.WISSEN.name())
                .setParameter(5, TaakStatus.OPEN.name())
                .executeUpdate();
    }

    /**
     * Plant een terugkoppeltaak voor elke dienstverlener met een webhook die er nog geen heeft.
     *
     * @return het aantal geplande taken
     */
    public int planTerugkoppeltaken(OffsetDateTime due) {
        // De unieke index op (dv_id, soort) voor terugkoppeltaken maakt dit veilig bij gelijktijdige pods.
        // Hibernate leidt de parametertypen in een insert niet af uit de kolommen; vandaar de types van
        // de attributen.
        return getSession().createMutationQuery("insert into Taak (" + Taak_.TAAK_SOORT + ", " + Taak_.DV_ID + ", "
                        + Taak_.DUE + ", " + Taak_.STATUS + ") select :taakSoort, d." + Dienstverlener_.ID + ", :due, :open"
                        + " from Dienstverlener d where d." + Dienstverlener_.WEBHOOK_URL + " is not null"
                        + " and not exists (select 1 from Taak t where t." + Taak_.TAAK_SOORT + " = :taakSoort and t."
                        + Taak_.DV_ID + " = d." + Dienstverlener_.ID + ") on conflict do nothing")
                .setParameter("taakSoort", TaakSoort.TERUGKOPPELEN, Taak_.taakSoort.getType())
                .setParameter("due", due, Taak_.due.getType())
                .setParameter("open", TaakStatus.OPEN, Taak_.status.getType())
                .executeUpdate();
    }

    /**
     * De dienstverleners met een taak van deze soort die aan de beurt is, wie het langst wacht eerst,
     * zodat bij een klein budget niet steeds dezelfde dienstverleners niets krijgen; {@code null} in de
     * lijst staat voor taken per systeem.
     */
    public List<UUID> dienstverlenersMetOpenWerk(TaakSoort taakSoort, OffsetDateTime nu) {
        return getEntityManager()
                .createQuery("select " + Taak_.DV_ID + " from Taak where " + AAN_DE_BEURT
                        + " group by " + Taak_.DV_ID + " order by min(" + Taak_.DUE + ")", UUID.class)
                .setParameter("taakSoort", taakSoort)
                .setParameter("open", TaakStatus.OPEN)
                .setParameter("nu", nu)
                .getResultList();
    }

    /**
     * Claimt tot {@code maximum} taken van deze soort en dienstverlener die aan de beurt zijn en niet
     * door een andere worker vastgehouden worden, en zet er een lease op tot {@code leaseTot}.
     *
     * @param dvId de dienstverlener, of {@code null} voor taken per systeem
     */
    public List<Taak> claim(TaakSoort taakSoort, UUID dvId, int maximum, OffsetDateTime nu, OffsetDateTime leaseTot) {
        Parameters parameters = Parameters.with("taakSoort", taakSoort).and("open", TaakStatus.OPEN).and("nu", nu);
        PanacheQuery<Taak> query = dvId == null
                ? find(AAN_DE_BEURT + " and " + Taak_.DV_ID + " is null", Sort.by(Taak_.DUE), parameters)
                : find(AAN_DE_BEURT + " and " + Taak_.DV_ID + " = :dvId", Sort.by(Taak_.DUE), parameters.and("dvId", dvId));

        // SKIP LOCKED slaat rijen over die een andere worker op dit moment claimt. De lease en het
        // epoch gaan bij de commit naar de database, met een toets op het oude epoch.
        List<Taak> taken = query
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .withHint(SpecHints.HINT_SPEC_LOCK_TIMEOUT, Timeouts.SKIP_LOCKED_MILLI)
                .page(0, maximum)
                .list();
        taken.forEach(taak -> taak.claim(leaseTot));

        return taken;
    }

    /**
     * Plant een receipttaak, tenzij dezelfde receipt (poging, status en tijdstip) er al staat.
     *
     * @return false als het een herhaling was
     */
    public boolean planReceipt(UUID dvId, UUID notificatieId, Map<String, String> payload, OffsetDateTime due) {
        // Hibernate leidt het parametertype in een insert niet af uit de kolom; zonder het type van het
        // attribuut gaat de payload als bytea en weigert PostgreSQL hem voor jsonb.
        return getSession().createMutationQuery("insert into Taak (" + Taak_.TAAK_SOORT + ", " + Taak_.DV_ID + ", "
                        + Taak_.NOTIFICATIE_ID + ", " + Taak_.DUE + ", " + Taak_.STATUS + ", " + Taak_.PAYLOAD
                        + ") values (:taakSoort, :dvId, :notificatieId, :due, :open, :payload) on conflict do nothing")
                .setParameter("taakSoort", TaakSoort.RECEIPT_VERWERKEN)
                .setParameter("dvId", dvId)
                .setParameter("notificatieId", notificatieId)
                .setParameter("due", due)
                .setParameter("open", TaakStatus.OPEN)
                .setParameter("payload", payload, Taak_.payload.getType())
                .executeUpdate() == 1;
    }

    /**
     * Verwijdert de open taak van deze soort voor de notificatie, ook als een worker hem net geclaimd
     * heeft; diens afronding vindt de taak dan niet meer en rolt terug.
     */
    public long verwijderOpen(TaakSoort taakSoort, UUID notificatieId) {
        return delete(Taak_.TAAK_SOORT + " = ?1 and " + Taak_.NOTIFICATIE_ID + " = ?2 and " + Taak_.STATUS + " = ?3",
                taakSoort, notificatieId, TaakStatus.OPEN);
    }

    /** Verlengt de lease; false als de taak inmiddels door een andere worker is geclaimd. */
    public boolean verlengLease(Taak taak, OffsetDateTime tot) {
        return update(Taak_.LEASE_TOT + " = :tot where " + ZELFDE_CLAIM, claimVan(taak).and("tot", tot)) == 1;
    }

    /** Verwijdert de afgeronde taak; false als de taak inmiddels door een andere worker is geclaimd. */
    public boolean rondAf(Taak taak) {
        return delete(ZELFDE_CLAIM, claimVan(taak)) == 1;
    }

    /**
     * Geeft de lease vrij en plant de taak opnieuw op {@code due}; false als de taak inmiddels door
     * een andere worker is geclaimd.
     *
     * @param teltAlsPoging of dit uitstel een mislukking in het NMC zelf was
     */
    public boolean stelUit(Taak taak, OffsetDateTime due, boolean teltAlsPoging) {
        return update(Taak_.LEASE_TOT + " = null, " + Taak_.DUE + " = :due, " + Taak_.POGINGEN + " = "
                        + Taak_.POGINGEN + " + :extra where " + ZELFDE_CLAIM,
                claimVan(taak).and("due", due).and("extra", teltAlsPoging ? 1 : 0)) == 1;
    }

    /**
     * Plant de taak opnieuw op {@code due} na een geslaagde ronde en zet de pogingen terug; false als
     * de taak inmiddels door een andere worker is geclaimd.
     */
    public boolean herplanNaSucces(Taak taak, OffsetDateTime due) {
        return update(Taak_.LEASE_TOT + " = null, " + Taak_.DUE + " = :due, " + Taak_.POGINGEN + " = 0 where "
                + ZELFDE_CLAIM, claimVan(taak).and("due", due)) == 1;
    }

    /** Zet de taak op mislukt en geeft de lease vrij; false als een andere worker hem inmiddels claimde. */
    public boolean markeerMislukt(Taak taak) {
        return update(Taak_.LEASE_TOT + " = null, " + Taak_.STATUS + " = :mislukt where " + ZELFDE_CLAIM,
                claimVan(taak).and("mislukt", TaakStatus.MISLUKT)) == 1;
    }

    public long telMetStatus(TaakSoort taakSoort, TaakStatus status) {
        return count(Taak_.TAAK_SOORT + " = ?1 and " + Taak_.STATUS + " = ?2", taakSoort, status);
    }

    /** Het aantal open taken van deze soort dat aan de beurt is: de achterstand. */
    public long telAchterstand(TaakSoort taakSoort, OffsetDateTime nu) {
        return count(Taak_.TAAK_SOORT + " = ?1 and " + Taak_.STATUS + " = ?2 and " + Taak_.DUE + " <= ?3",
                taakSoort, TaakStatus.OPEN, nu);
    }

    public Optional<Taak> zoek(TaakSoort taakSoort, long id) {
        return find(Taak_.TAAK_SOORT + " = ?1 and " + Taak_.ID + " = ?2", taakSoort, id).singleResultOptional();
    }

    private static Parameters claimVan(Taak taak) {
        return Parameters.with("taakSoort", taak.getTaakSoort())
                .and("id", taak.getId())
                .and("claimEpoch", taak.getClaimEpoch());
    }
}
