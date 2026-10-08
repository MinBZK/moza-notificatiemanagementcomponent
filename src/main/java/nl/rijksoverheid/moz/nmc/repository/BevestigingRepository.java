package nl.rijksoverheid.moz.nmc.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.Bevestiging;
import nl.rijksoverheid.moz.nmc.domain.Cursor;
import org.hibernate.query.NativeQuery;
import org.hibernate.type.StandardBasicTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * De bevestiging per dienstverlener. Geen entity: de kolom xid is een xid8, die Hibernate niet als
 * getal leest, dus beide statements zijn native SQL met een cast via text.
 */
@ApplicationScoped
public class BevestigingRepository {

    // De rij gaat alleen vooruit: de WHERE op de conflictkant laat een oudere cursor staan, en
    // executeUpdate geeft dan 0.
    private static final String ZET_VOORUIT_SQL = """
            INSERT INTO bevestiging (dv_id, epoch, xid, event_id, bevestigd_op)
            VALUES (?1, ?2, CAST(CAST(?3 AS text) AS xid8), ?4, ?5)
            ON CONFLICT (dv_id) DO UPDATE
               SET epoch = EXCLUDED.epoch, xid = EXCLUDED.xid, event_id = EXCLUDED.event_id,
                   bevestigd_op = EXCLUDED.bevestigd_op
             WHERE (bevestiging.epoch, bevestiging.xid, bevestiging.event_id)
                 < (EXCLUDED.epoch, EXCLUDED.xid, EXCLUDED.event_id)
            """;

    private static final String ZOEK_SQL = """
            SELECT epoch, xid::text::bigint AS xid_nr, event_id, bevestigd_op
              FROM bevestiging
             WHERE dv_id = ?1
            """;

    private final EntityManager entityManager;

    public BevestigingRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Schrijft de cursor als hij verder ligt dan de bewaarde; false als de bewaarde al even ver of verder was. */
    public boolean zetVooruit(UUID dvId, Cursor cursor, OffsetDateTime bevestigdOp) {
        return entityManager.createNativeQuery(ZET_VOORUIT_SQL)
                .setParameter(1, dvId)
                .setParameter(2, cursor.epoch())
                .setParameter(3, cursor.xid())
                .setParameter(4, cursor.eventId())
                .setParameter(5, bevestigdOp)
                .executeUpdate() == 1;
    }

    @SuppressWarnings("unchecked")
    public Optional<Bevestiging> zoek(UUID dvId) {
        List<Object[]> rijen = entityManager.createNativeQuery(ZOEK_SQL)
                .unwrap(NativeQuery.class)
                .addScalar("epoch", StandardBasicTypes.INTEGER)
                .addScalar("xid_nr", StandardBasicTypes.LONG)
                .addScalar("event_id", StandardBasicTypes.LONG)
                .addScalar("bevestigd_op", StandardBasicTypes.OFFSET_DATE_TIME)
                .setParameter(1, dvId)
                .getResultList();

        return rijen.stream().findFirst().map(rij -> new Bevestiging(dvId,
                new Cursor((Integer) rij[0], (Long) rij[1], (Long) rij[2]), (OffsetDateTime) rij[3]));
    }
}
