package nl.rijksoverheid.moz.nmc.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.Cursor;
import nl.rijksoverheid.moz.nmc.domain.Webhookpositie;
import org.hibernate.query.NativeQuery;
import org.hibernate.type.StandardBasicTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * De leverpositie van de webhook per dienstverlener. Geen entity: de kolom xid is een xid8, die
 * Hibernate niet als getal leest, dus de statements zijn native SQL met een cast via text.
 */
@ApplicationScoped
public class WebhookpositieRepository {

    private static final String ZOEK_SQL = """
            SELECT epoch, xid::text::bigint AS xid_nr, event_id, mislukkingen, gepauzeerd_tot
              FROM webhookpositie
             WHERE dv_id = ?1
            """;

    private static final String BEWAAR_SQL = """
            INSERT INTO webhookpositie (dv_id, epoch, xid, event_id, mislukkingen, gepauzeerd_tot, bijgewerkt_op)
            VALUES (?1, CAST(?2 AS integer), CAST(CAST(?3 AS text) AS xid8), CAST(?4 AS bigint), ?5,
                    CAST(?6 AS timestamptz), ?7)
            ON CONFLICT (dv_id) DO UPDATE
               SET epoch = EXCLUDED.epoch, xid = EXCLUDED.xid, event_id = EXCLUDED.event_id,
                   mislukkingen = EXCLUDED.mislukkingen, gepauzeerd_tot = EXCLUDED.gepauzeerd_tot,
                   bijgewerkt_op = EXCLUDED.bijgewerkt_op
            """;

    private final EntityManager entityManager;

    public WebhookpositieRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** De bewaarde positie, of een nieuwe als er voor deze dienstverlener nog geen rij is. */
    @SuppressWarnings("unchecked")
    public Webhookpositie zoek(UUID dvId) {
        List<Object[]> rijen = entityManager.createNativeQuery(ZOEK_SQL)
                .unwrap(NativeQuery.class)
                .addScalar("epoch", StandardBasicTypes.INTEGER)
                .addScalar("xid_nr", StandardBasicTypes.LONG)
                .addScalar("event_id", StandardBasicTypes.LONG)
                .addScalar("mislukkingen", StandardBasicTypes.INTEGER)
                .addScalar("gepauzeerd_tot", StandardBasicTypes.OFFSET_DATE_TIME)
                .setParameter(1, dvId)
                .getResultList();

        return rijen.stream().findFirst()
                .map(rij -> new Webhookpositie(dvId,
                        rij[0] == null ? null : new Cursor((Integer) rij[0], (Long) rij[1], (Long) rij[2]),
                        (Integer) rij[3], (OffsetDateTime) rij[4]))
                .orElseGet(() -> Webhookpositie.nieuw(dvId));
    }

    @SuppressWarnings("unchecked")
    public void bewaar(Webhookpositie positie, OffsetDateTime nu) {
        Optional<Cursor> cursor = positie.laatstGeleverd();
        // Getypeerd gebonden, zodat een lege positie of pauze als null van het juiste type aankomt.
        entityManager.createNativeQuery(BEWAAR_SQL)
                .unwrap(NativeQuery.class)
                .setParameter(1, positie.dvId())
                .setParameter(2, cursor.map(Cursor::epoch).orElse(null), StandardBasicTypes.INTEGER)
                .setParameter(3, cursor.map(Cursor::xid).orElse(null), StandardBasicTypes.LONG)
                .setParameter(4, cursor.map(Cursor::eventId).orElse(null), StandardBasicTypes.LONG)
                .setParameter(5, positie.mislukkingen())
                .setParameter(6, positie.gepauzeerdTot(), StandardBasicTypes.OFFSET_DATE_TIME)
                .setParameter(7, nu)
                .executeUpdate();
    }
}
