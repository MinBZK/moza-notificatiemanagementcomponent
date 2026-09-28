package nl.rijksoverheid.moz.nmc.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import org.hibernate.query.NativeQuery;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
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

    /**
     * Claimt tot {@code maximum} verlopen notificaties met {@code FOR UPDATE SKIP LOCKED} en geeft
     * hun ids terug. De rijen blijven vergrendeld tot de transactie eindigt, zodat een gelijktijdige
     * statuswijziging erop blokkeert en ze tussen claim en verwijdering niet kunnen veranderen.
     *
     * @param uitgesloten ids die overgeslagen moeten worden; leeg laten als er niets uit te sluiten is
     */
    @SuppressWarnings("unchecked")
    public List<UUID> claimVerlopen(OffsetDateTime grens, int maximum, List<UUID> uitgesloten) {
        // addScalar legt het resultaattype vast op UUID, los van wat de driver voor een uuid-kolom kiest.
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

    /** Het aantal notificaties dat deze dienstverlener sinds {@code vanaf} heeft aangeboden. */
    public long telAangenomenSinds(UUID dvId, OffsetDateTime vanaf) {
        return count("dvId = ?1 and aangenomenOp >= ?2", dvId, vanaf);
    }

    /**
     * Notificaties zonder terminale status waarvoor geen taak open staat of mislukt is: die zijn
     * blijven steken en krijgen van de controletaak de ontbrekende taak.
     */
    public List<Notificatie> zonderTaak(Collection<NotificatieStatus> terminaal, int limiet) {
        return getEntityManager()
                .createQuery("SELECT n FROM Notificatie n WHERE n.status NOT IN :terminaal "
                        + "AND NOT EXISTS (SELECT t.id FROM Taak t WHERE t.notificatieId = n.id) "
                        + "ORDER BY n.laatsteStatusUpdate", Notificatie.class)
                .setParameter("terminaal", terminaal)
                .setMaxResults(limiet)
                .getResultList();
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
     * Verwijdert de opgegeven notificaties. De pogingen gaan mee via {@code ON DELETE CASCADE}; de
     * events blijven staan, het eventlog wordt per partitie opgeruimd.
     */
    public int verwijderOpId(List<UUID> ids) {
        return getEntityManager()
                .createNamedQuery(Notificatie.VERWIJDER_OP_ID)
                .setParameter("ids", ids)
                .executeUpdate();
    }
}
