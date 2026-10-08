package nl.rijksoverheid.moz.nmc.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import org.hibernate.query.NativeQuery;
import org.hibernate.type.StandardBasicTypes;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * De partities van het eventlog en wat ze tegenhoudt. Alles is native SQL: het gaat om de catalogus,
 * DDL en de kolom xid (xid8), die Hibernate niet als getal leest.
 * <p>
 * {@code event} is gepartitioneerd op bereiken van transactie-id, met {@code event_standaard} als
 * default-partitie voor wat buiten de bereiken valt.
 */
@ApplicationScoped
public class EventPartitieRepository {

    // De grenzen staan alleen in de partitiedefinitie, als FOR VALUES FROM ('van') TO ('tot').
    private static final String BEREIKPARTITIES_SQL = """
            SELECT naam, grens[1]::bigint AS van, grens[2]::bigint AS tot
              FROM (SELECT c.relname::text AS naam,
                           regexp_match(pg_get_expr(c.relpartbound, c.oid),
                                        'FROM \\(''(\\d+)''\\) TO \\(''(\\d+)''\\)') AS grens
                      FROM pg_inherits i
                      JOIN pg_class c ON c.oid = i.inhrelid
                     WHERE i.inhparent = 'event'::regclass) p
             WHERE grens IS NOT NULL
             ORDER BY 2
            """;

    // Alleen cursors uit het huidige epoch en jonger dan de maximale cursorleeftijd van hun
    // dienstverlener tellen; een webhookpositie telt vanaf de laatste levering, niet vanaf een
    // mislukte poging.
    private static final String OUDSTE_BESCHERMDE_XID_SQL = """
            SELECT min(c.xid::text::bigint) AS xid_nr
              FROM (SELECT b.xid
                      FROM bevestiging b
                      JOIN dienstverlener d ON d.id = b.dv_id
                     WHERE b.epoch = ?1
                       AND b.bevestigd_op > CAST(?2 AS timestamptz)
                           - COALESCE(d.max_cursorleeftijd, CAST(?3 AS bigint) * interval '1 second')
                    UNION ALL
                    SELECT w.xid
                      FROM webhookpositie w
                      JOIN dienstverlener d ON d.id = w.dv_id
                     WHERE w.epoch = ?1
                       AND w.geleverd_op > CAST(?2 AS timestamptz)
                           - COALESCE(d.max_cursorleeftijd, CAST(?3 AS bigint) * interval '1 second')) c
            """;

    private static final String VERWIJDER_UIT_STANDAARD_SQL = """
            DELETE FROM event_standaard
             WHERE (xid, id) IN (SELECT xid, id
                                   FROM event_standaard
                                  WHERE tijdstip < ?1
                                    AND xid < CAST(CAST(?2 AS text) AS xid8)
                                  FETCH FIRST ?3 ROWS ONLY)
            """;

    private final EntityManager entityManager;

    public EventPartitieRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** De bereikpartities, oudste eerst; de default-partitie hoort er niet bij. */
    @SuppressWarnings("unchecked")
    public List<EventPartitie> bereikpartities() {
        List<Object[]> rijen = entityManager.createNativeQuery(BEREIKPARTITIES_SQL)
                .unwrap(NativeQuery.class)
                .addScalar("naam", StandardBasicTypes.STRING)
                .addScalar("van", StandardBasicTypes.LONG)
                .addScalar("tot", StandardBasicTypes.LONG)
                .getResultList();

        return rijen.stream().map(rij -> new EventPartitie((String) rij[0], (Long) rij[1], (Long) rij[2])).toList();
    }

    /** De transactie-id die als volgende wordt uitgedeeld. */
    public long volgendeXid() {
        return xid("SELECT pg_snapshot_xmax(pg_current_snapshot())::text::bigint AS xid_nr");
    }

    /** De oudste lopende transactie: onder deze transactie-id komt geen event meer bij. */
    public long watermerk() {
        return xid("SELECT pg_snapshot_xmin(pg_current_snapshot())::text::bigint AS xid_nr");
    }

    /** Laat DDL in de lopende transactie opgeven in plaats van wachten als het eventlog bezet is. */
    public void zetLockTimeout(Duration timeout) {
        entityManager.createNativeQuery("SET LOCAL lock_timeout = '" + timeout.toMillis() + "ms'").executeUpdate();
    }

    /** Houdt tot het einde van de transactie elke lezer en schrijver van het eventlog tegen. */
    public void vergrendelEventlog() {
        entityManager.createNativeQuery("LOCK TABLE event IN ACCESS EXCLUSIVE MODE").executeUpdate();
    }

    public void maak(EventPartitie partitie) {
        entityManager.createNativeQuery("CREATE TABLE " + partitie.naam() + " PARTITION OF event FOR VALUES FROM ('"
                + partitie.van() + "') TO ('" + partitie.tot() + "')").executeUpdate();
    }

    public void verwijder(EventPartitie partitie) {
        entityManager.createNativeQuery("DROP TABLE " + partitie.naam()).executeUpdate();
    }

    /**
     * Het tijdstip van het event met de hoogste transactie-id in de partitie; leeg als de partitie leeg
     * is. Via de primary key, zonder de partitie te scannen; een event met een lagere transactie-id kan
     * hooguit de transactie-timeout later geregistreerd zijn.
     */
    @SuppressWarnings("unchecked")
    public Optional<OffsetDateTime> jongsteEvent(EventPartitie partitie) {
        List<OffsetDateTime> rijen = entityManager.createNativeQuery("SELECT tijdstip FROM " + partitie.naam()
                        + " ORDER BY xid DESC, id DESC FETCH FIRST 1 ROWS ONLY")
                .unwrap(NativeQuery.class)
                .addScalar("tijdstip", StandardBasicTypes.OFFSET_DATE_TIME)
                .getResultList();

        return rijen.stream().findFirst();
    }

    /**
     * De laagste transactie-id waar een geldige bevestiging of leverpositie naar wijst; leeg als geen
     * enkele cursor het opruimen tegenhoudt.
     *
     * @param standaardLeeftijd de maximale cursorleeftijd voor een dienstverlener zonder eigen afspraak
     */
    @SuppressWarnings("unchecked")
    public Optional<Long> oudsteBeschermdeXid(int epoch, OffsetDateTime nu, Duration standaardLeeftijd) {
        List<Long> rijen = entityManager.createNativeQuery(OUDSTE_BESCHERMDE_XID_SQL)
                .unwrap(NativeQuery.class)
                .addScalar("xid_nr", StandardBasicTypes.LONG)
                .setParameter(1, epoch)
                .setParameter(2, nu)
                .setParameter(3, standaardLeeftijd.toSeconds())
                .getResultList();

        return rijen.stream().filter(xid -> xid != null).findFirst();
    }

    /**
     * Verwijdert tot {@code maximum} events uit de default-partitie die vóór {@code grens} zijn
     * geregistreerd en een transactie-id onder {@code onderXid} hebben.
     *
     * @return het aantal verwijderde events
     */
    public int verwijderUitStandaardpartitie(OffsetDateTime grens, long onderXid, int maximum) {
        return entityManager.createNativeQuery(VERWIJDER_UIT_STANDAARD_SQL)
                .setParameter(1, grens)
                .setParameter(2, onderXid)
                .setParameter(3, maximum)
                .executeUpdate();
    }

    @SuppressWarnings("unchecked")
    private long xid(String sql) {
        List<Long> rijen = entityManager.createNativeQuery(sql)
                .unwrap(NativeQuery.class)
                .addScalar("xid_nr", StandardBasicTypes.LONG)
                .getResultList();

        return rijen.getFirst();
    }
}
