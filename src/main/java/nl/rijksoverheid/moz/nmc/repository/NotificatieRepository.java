package nl.rijksoverheid.moz.nmc.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import org.hibernate.query.NativeQuery;
import org.hibernate.type.StandardBasicTypes;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class NotificatieRepository implements PanacheRepositoryBase<Notificatie, UUID> {

    // SKIP LOCKED: een notificatie die een overgang of een andere pod vasthoudt, komt een volgende
    // ronde aan de beurt. Pogingen en taken gaan mee via ON DELETE CASCADE.
    private static final String VERWIJDER_NA_BEWAARTERMIJN_SQL = """
            DELETE FROM notificatie
             WHERE id IN (SELECT id
                            FROM notificatie
                           WHERE terminaal_op < ?1
                           ORDER BY terminaal_op
                           FETCH FIRST ?2 ROWS ONLY
                             FOR UPDATE SKIP LOCKED)
            """;

    // Op id, zodat een rij waarvan de oude KEK ontbreekt de ronde niet blokkeert: de volgende batch
    // begint erachter.
    private static final String HERWRAP_KANDIDATEN_SQL = """
            SELECT id, sleutel_gewrapt, kek_versie
              FROM notificatie
             WHERE kek_versie < ?1
               AND sleutel_gewrapt IS NOT NULL
               AND id > ?2
             ORDER BY id
             FETCH FIRST ?3 ROWS ONLY
               FOR UPDATE SKIP LOCKED
            """;

    // Native SQL zonder de versie: wissen en herwrappen zijn geen overgang en schrijven geen event.
    private static final String WIS_SLEUTEL_SQL =
            "UPDATE notificatie SET sleutel_gewrapt = NULL, kek_versie = NULL WHERE id = ?1";

    private static final String ZET_SLEUTEL_SQL =
            "UPDATE notificatie SET sleutel_gewrapt = ?2, kek_versie = ?3 WHERE id = ?1";

    /**
     * Verwijdert tot {@code maximum} notificaties die vóór {@code grens} terminaal werden, met hun
     * pogingen en taken.
     *
     * @return het aantal verwijderde notificaties
     */
    public int verwijderTerminaalVoor(OffsetDateTime grens, int maximum) {
        return getEntityManager().createNativeQuery(VERWIJDER_NA_BEWAARTERMIJN_SQL)
                .setParameter(1, grens)
                .setParameter(2, maximum)
                .executeUpdate();
    }

    /**
     * Vergrendelt tot {@code maximum} notificaties met een sleutel onder een oudere KEK-versie dan
     * {@code huidigeVersie}, met een id na {@code naId}, op volgorde van id.
     */
    @SuppressWarnings("unchecked")
    public List<GewrapteSleutel> vergrendelOudeSleutels(int huidigeVersie, UUID naId, int maximum) {
        List<Object[]> rijen = getEntityManager().createNativeQuery(HERWRAP_KANDIDATEN_SQL)
                .unwrap(NativeQuery.class)
                .addScalar("id", UUID.class)
                .addScalar("sleutel_gewrapt", StandardBasicTypes.BINARY)
                .addScalar("kek_versie", StandardBasicTypes.INTEGER)
                .setParameter(1, huidigeVersie)
                .setParameter(2, naId)
                .setParameter(3, maximum)
                .getResultList();

        return rijen.stream().map(rij -> new GewrapteSleutel((UUID) rij[0], (byte[]) rij[1], (Integer) rij[2])).toList();
    }

    /** Wist de gewrapte sleutel en de KEK-versie, zonder de versie van de notificatie te verhogen. */
    public void wisSleutel(UUID notificatieId) {
        getEntityManager().createNativeQuery(WIS_SLEUTEL_SQL)
                .setParameter(1, notificatieId)
                .executeUpdate();
    }

    /** Vervangt de gewrapte sleutel, zonder de versie van de notificatie te verhogen. */
    public void zetSleutel(UUID notificatieId, byte[] sleutelGewrapt, int kekVersie) {
        getEntityManager().createNativeQuery(ZET_SLEUTEL_SQL)
                .setParameter(1, notificatieId)
                .setParameter(2, sleutelGewrapt)
                .setParameter(3, kekVersie)
                .executeUpdate();
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
                        + "ORDER BY n.aangenomenOp", Notificatie.class)
                .setParameter("terminaal", terminaal)
                .setMaxResults(limiet)
                .getResultList();
    }
}
