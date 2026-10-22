package nl.rijksoverheid.moz.nmc.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Notificatie_;
import nl.rijksoverheid.moz.nmc.domain.TaakStatus;
import nl.rijksoverheid.moz.nmc.domain.Taak_;
import org.hibernate.query.NativeQuery;
import org.hibernate.type.StandardBasicTypes;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class NotificatieRepository implements PanacheRepositoryBase<Notificatie, UUID> {

    // Native vanwege FOR UPDATE SKIP LOCKED in de subselect: een notificatie die een overgang of een
    // andere pod vasthoudt, komt een volgende ronde aan de beurt. Pogingen en taken gaan mee via
    // ON DELETE CASCADE.
    private static final String VERWIJDER_MET_EINDSTATUS_OUDER_DAN_SQL = """
            DELETE FROM notificatie
             WHERE id IN (SELECT id
                            FROM notificatie
                           WHERE eindstatus_op < ?1
                           ORDER BY eindstatus_op
                           FETCH FIRST ?2 ROWS ONLY
                             FOR UPDATE SKIP LOCKED)
            """;

    // Native: een vergrendelde projectie (FOR UPDATE SKIP LOCKED) van alleen de sleutelkolommen, zodat
    // niet de hele rij wordt geladen. Op id, zodat een rij waarvan de oude KEK ontbreekt de ronde niet
    // blokkeert: de volgende batch begint erachter.
    private static final String VERGRENDEL_OUDE_SLEUTELS_SQL = """
            SELECT id, sleutel_gewrapt, kek_versie
              FROM notificatie
             WHERE kek_versie < ?1
               AND sleutel_gewrapt IS NOT NULL
               AND id > ?2
             ORDER BY id
             FETCH FIRST ?3 ROWS ONLY
               FOR UPDATE SKIP LOCKED
            """;

    // Zonder de versie en zonder event: wissen en herwrappen zijn geen overgang. Native, omdat deze
    // kolommen op de entity updatable = false staan.
    private static final String WIS_SLEUTEL_SQL =
            "UPDATE notificatie SET sleutel_gewrapt = NULL, kek_versie = NULL WHERE id = ?1";

    private static final String ZET_SLEUTEL_SQL =
            "UPDATE notificatie SET sleutel_gewrapt = ?2, kek_versie = ?3 WHERE id = ?1";

    /** Leest de notificatie met een rijlock die tot het einde van de transactie blijft staan. */
    public Optional<Notificatie> vergrendel(UUID id) {
        return findByIdOptional(id, LockModeType.PESSIMISTIC_WRITE);
    }

    /** Verhoogt de versie van een vergrendelde notificatie waarvan geen veld verandert. */
    public void verhoogVersie(Notificatie notificatie) {
        getEntityManager().lock(notificatie, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }

    /**
     * Verwijdert tot {@code maximum} notificaties die vóór {@code grens} een eindstatus kregen, met hun
     * pogingen en taken.
     *
     * @return het aantal verwijderde notificaties
     */
    public int verwijderMetEindstatusOuderDan(OffsetDateTime grens, int maximum) {
        return getEntityManager().createNativeQuery(VERWIJDER_MET_EINDSTATUS_OUDER_DAN_SQL)
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
        List<Object[]> rijen = getEntityManager().createNativeQuery(VERGRENDEL_OUDE_SLEUTELS_SQL)
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
        return count(Notificatie_.DV_ID + " = ?1 and " + Notificatie_.AANGENOMEN_OP + " >= ?2", dvId, vanaf);
    }

    /**
     * Notificaties met een open status waarvoor geen taak open staat: die zijn blijven steken en krijgen
     * van de controletaak de ontbrekende taak. Een mislukte taak houdt de notificatie tegen tot
     * {@code geldig_tot} (zonder {@code geldig_tot} voor altijd); daarna telt hij niet meer mee, zodat de
     * notificatie alsnog een eindstatus krijgt en gewist kan worden. De open statussen gaan als lijst mee en de volgorde is het
     * aannametijdstip, passend bij de partiële index op de open rijen uit V9.
     */
    public List<Notificatie> zonderTaak(Collection<NotificatieStatus> openStatussen, OffsetDateTime nu, int limiet) {
        // De subquery noemt geen soort: een taak van elke soort telt, dus hij leest elke partitie.
        return getEntityManager()
                .createQuery("select n from Notificatie n where n." + Notificatie_.STATUS + " in :statussen "
                        + "and not exists (select t." + Taak_.ID + " from Taak t where t." + Taak_.NOTIFICATIE_ID
                        + " = n." + Notificatie_.ID + " and (t." + Taak_.STATUS + " = :open or n." + Notificatie_.GELDIG_TOT
                        + " is null or n." + Notificatie_.GELDIG_TOT + " > :nu)) order by n." + Notificatie_.AANGENOMEN_OP, Notificatie.class)
                .setParameter("statussen", openStatussen)
                .setParameter("open", TaakStatus.OPEN)
                .setParameter("nu", nu)
                .setMaxResults(limiet)
                .getResultList();
    }
}
