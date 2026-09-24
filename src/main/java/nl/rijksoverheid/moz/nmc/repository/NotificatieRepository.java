package nl.rijksoverheid.moz.nmc.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.Notificatie_;
import org.hibernate.query.NativeQuery;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class NotificatieRepository implements PanacheRepositoryBase<Notificatie, UUID> {

    // Native SQL omdat JPQL geen lock-clausule met SKIP LOCKED kent. Zie
    // RetentieBatch#verwijder voor waarom die clausule er staat. %s is leeg of de
    // uitsluiting hieronder, zodat beide varianten maar één keer beschreven staan.
    private static final String CLAIM_VERLOPEN_SQL = """
            SELECT id
              FROM notificatie
             WHERE laatste_status_update <= ?1%s
             ORDER BY laatste_status_update
             FETCH FIRST ?2 ROWS ONLY
               FOR UPDATE SKIP LOCKED
            """;

    // Slaat rijen over die in een eerdere batch mislukten; zie de batchlus in de scheduler.
    private static final String UITSLUITING_SQL = "\n   AND id NOT IN (?3)";

    public Optional<Notificatie> findByExternalReference(UUID externalReference) {
        return find(Notificatie_.EXTERNAL_REFERENCE, externalReference).singleResultOptional();
    }

    /**
     * Claimt tot {@code maximum} verlopen notificaties met {@code FOR UPDATE SKIP LOCKED} en geeft
     * hun ids terug. De rijen blijven vergrendeld tot de transactie eindigt, zodat een gelijktijdige
     * statuswijziging erop blokkeert en ze tussen claim en verwijdering niet kunnen veranderen.
     *
     * @param uitgesloten ids die overgeslagen moeten worden; leeg laten als er niets uit te sluiten is
     */
    @SuppressWarnings("unchecked")
    public List<UUID> claimVerlopen(OffsetDateTime grens, int maximum, List<UUID> uitgesloten) {
        // addScalar is nodig omdat een native query een uuid-kolom per dialect anders oplevert:
        // PostgreSQL een UUID, H2 een byte[].
        NativeQuery<UUID> query = getEntityManager()
                .createNativeQuery(CLAIM_VERLOPEN_SQL.formatted(uitgesloten.isEmpty() ? "" : UITSLUITING_SQL))
                .unwrap(NativeQuery.class)
                .addScalar("id", UUID.class)
                .setParameter(1, grens)
                .setParameter(2, maximum);

        if (!uitgesloten.isEmpty()) {
            query.setParameter(3, uitgesloten);
        }

        return query.getResultList();
    }

    /**
     * De gegevens die de retentiejob per notificatie meldt, oudste eerst.
     * <p>
     * Een JPQL-constructorexpressie en geen kolommen uit de native query hierboven: de expressie
     * noemt de recordcomponenten op type, zodat een verkeerde ariteit of een niet-passend type een
     * fout op de query zelf geeft in plaats van een {@code ClassCastException} verderop. De
     * {@code ORDER BY} staat er omdat {@code IN} geen volgorde garandeert.
     */
    public List<Kandidaat> zoekKandidaten(List<UUID> ids) {
        return getEntityManager()
                .createNamedQuery(Notificatie.ZOEK_KANDIDATEN, Kandidaat.class)
                .setParameter("ids", ids)
                .getResultList();
    }

    /**
     * Verwijdert de opgegeven notificaties en hun statusgeschiedenis.
     * <p>
     * JPQL en geen native SQL, zodat Hibernate de {@code notificatie_status}-rijen zelf opruimt; de
     * {@code ON DELETE CASCADE} op de foreignkey blijft het vangnet voor verwijderingen die Hibernate
     * omzeilen.
     */
    public int verwijderOpId(List<UUID> ids) {
        return getEntityManager()
                .createNamedQuery(Notificatie.VERWIJDER_OP_ID)
                .setParameter("ids", ids)
                .executeUpdate();
    }
}
