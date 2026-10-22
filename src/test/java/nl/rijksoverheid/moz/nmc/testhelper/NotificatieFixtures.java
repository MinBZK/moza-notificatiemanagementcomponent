package nl.rijksoverheid.moz.nmc.testhelper;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.Event_;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Notificatie_;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.Poging_;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Schrijft rijen buiten de overgangsfunctie om, voor tests die een toestand willen die de applicatie
 * zelf niet maakt: een willekeurige status met een terug- of vooruitgedateerde registratietijd of
 * {@code eindstatus_op}. Staat op één plek zodat een kolomwijziging niet door meerdere testklassen hoeft.
 * <p>
 * Elke methode verwacht een lopende transactie. De constraint trigger op {@code notificatie} eist een
 * event per versie; de inserts hier zetten hem voor de eigen transactie uit.
 */
public final class NotificatieFixtures {

    /** De dienstverlener uit migratie V8 en {@code nmc.dienstverlener.id} onder {@code %test}. */
    public static final UUID DV_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private NotificatieFixtures() {
    }

    /**
     * Een notificatie in de gegeven status, zonder poging.
     *
     * @param eindstatusOp leeg zonder eindstatus
     */
    public static void voegNotificatieToe(EntityManager entityManager, UUID id, NotificatieStatus status,
                                          OffsetDateTime eindstatusOp) {
        zetTriggerUit(entityManager);
        entityManager.createQuery("insert into Notificatie (" + Notificatie_.ID + ", " + Notificatie_.DV_ID + ", "
                        + Notificatie_.VERSIE + ", " + Notificatie_.STATUS + ", " + Notificatie_.EINDSTATUS_OP
                        + ") values (:id, :dvId, 0, :status, :eindstatusOp)")
                .setParameter("id", id)
                .setParameter("dvId", DV_ID)
                .setParameter("status", status)
                .setParameter("eindstatusOp", eindstatusOp)
                .executeUpdate();
    }

    /** Een poging bij een bestaande notificatie, met het NotifyNL-id waarop een receipt binnenkomt. */
    public static void voegPogingToe(EntityManager entityManager, UUID notificatieId, int nummer, UUID notifyId,
                                     PogingStatus status, OffsetDateTime verzondenOp) {
        entityManager.createQuery("insert into Poging (" + Poging_.ID + ", " + Poging_.NOTIFICATIE_ID + ", "
                        + Poging_.NUMMER + ", " + Poging_.STATUS + ", " + Poging_.NOTIFY_ID + ", " + Poging_.VERZONDEN_OP
                        + ") values (:id, :notificatieId, :nummer, :status, :notifyId, :verzondenOp)")
                .setParameter("id", UUID.randomUUID())
                .setParameter("notificatieId", notificatieId)
                .setParameter("nummer", nummer)
                .setParameter("status", status)
                .setParameter("notifyId", notifyId)
                .setParameter("verzondenOp", verzondenOp)
                .executeUpdate();
    }

    /** Een event met een eigen registratietijd. */
    public static void voegEventToe(EntityManager entityManager, UUID notificatieId, long volgnummer,
                                    NotificatieStatus van, NotificatieStatus naar, OffsetDateTime geregistreerd) {
        entityManager.createQuery("insert into Event (" + Event_.GEREGISTREERD + ", " + Event_.DV_ID + ", "
                        + Event_.NOTIFICATIE_ID + ", " + Event_.VOLGNUMMER + ", " + Event_.VAN + ", " + Event_.NAAR
                        + ") values (:geregistreerd, :dvId, :notificatieId, :volgnummer, :van, :naar)")
                .setParameter("geregistreerd", geregistreerd)
                .setParameter("dvId", DV_ID)
                .setParameter("notificatieId", notificatieId)
                .setParameter("volgnummer", volgnummer)
                .setParameter("van", van)
                .setParameter("naar", naar)
                .executeUpdate();
    }

    /** De transactie-id waaronder het event van deze notificatie is geschreven. */
    public static long transactieIdVan(EntityManager entityManager, UUID notificatieId) {
        return entityManager.createQuery("select " + Event_.TRANSACTIE_ID + " from Event where " + Event_.NOTIFICATIE_ID
                        + " = :notificatieId", Long.class)
                .setParameter("notificatieId", notificatieId)
                .getSingleResult();
    }

    /** Verwijdert de events onder deze transactie-id, in welke partitie ze ook staan. */
    public static void verwijderEventsOnder(EntityManager entityManager, long transactieId) {
        entityManager.createQuery("delete from Event where " + Event_.TRANSACTIE_ID + " < :transactieId")
                .setParameter("transactieId", transactieId)
                .executeUpdate();
    }

    public static long telEvents(EntityManager entityManager, UUID notificatieId) {
        return entityManager.createQuery("select count(*) from Event where " + Event_.NOTIFICATIE_ID + " = :notificatieId",
                        Long.class)
                .setParameter("notificatieId", notificatieId)
                .getSingleResult();
    }

    /** De partitie waarin het event van deze notificatie staat. */
    public static String partitieVan(EntityManager entityManager, UUID notificatieId) {
        // Native: tableoid bestaat alleen in SQL.
        return (String) entityManager.createNativeQuery("SELECT tableoid::regclass::text FROM event WHERE notificatie_id = ?1")
                .setParameter(1, notificatieId)
                .getSingleResult();
    }

    /** Laat de lopende transactie een transactie-id uitdelen, zodat de reeks er één opschuift. */
    public static void verbruikTransactieId(EntityManager entityManager) {
        // Native: een PostgreSQL-functie zonder tabel.
        entityManager.createNativeQuery("SELECT pg_current_xact_id()::text").getSingleResult();
    }

    public static void verwijderNotificatie(EntityManager entityManager, UUID id) {
        entityManager.createQuery("delete from Notificatie where " + Notificatie_.ID + " = :id")
                .setParameter("id", id)
                .executeUpdate();
    }

    /** Het aantal transacties op deze database dat op een vergrendeling wacht. */
    public static long telTransactiesWachtendOpLock(EntityManager entityManager) {
        // Binnen één transactie geeft pg_stat_activity steeds het beeld van de eerste raadpleging; zonder
        // dit wissen ziet een poller die in zijn eigen transactie blijft nooit een later wachtende sessie.
        entityManager.createNativeQuery("SELECT pg_stat_clear_snapshot()").getSingleResult();

        return ((Number) entityManager.createNativeQuery("SELECT count(*) FROM pg_stat_activity "
                + "WHERE datname = current_database() AND wait_event_type = 'Lock'").getSingleResult()).longValue();
    }

    /** Zet de status rechtstreeks, zonder overgang en zonder versieverhoging; de trigger staat daarvoor uit. */
    public static void zetStatus(EntityManager entityManager, UUID id, NotificatieStatus status) {
        zetTriggerUit(entityManager);
        entityManager.createQuery("update Notificatie set " + Notificatie_.STATUS + " = :status where " + Notificatie_.ID + " = :id")
                .setParameter("status", status)
                .setParameter("id", id)
                .executeUpdate();
    }

    /**
     * De notificatierij zoals een {@code SELECT} van een beheerder hem ziet: elke kolom als bytes, bytea zoals
     * opgeslagen en andere typen in hun tekstvorm.
     */
    public static Map<String, byte[]> leesRuweRij(DataSource dataSource, UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM notificatie WHERE id = ?")) {
            statement.setObject(1, id);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("notificatie " + id + " niet gevonden");
                }

                ResultSetMetaData metaData = resultSet.getMetaData();
                Map<String, byte[]> rij = new LinkedHashMap<>();

                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    Object waarde = resultSet.getObject(i);
                    byte[] bytes = waarde instanceof byte[] b ? b
                            : waarde == null ? null : String.valueOf(waarde).getBytes(StandardCharsets.UTF_8);
                    rij.put(metaData.getColumnName(i), bytes);
                }

                return rij;
            }
        }
    }

    /** Zet het moment van de eindstatus terug, zodat wistermijn of bewaartermijn verstreken is. */
    public static void verzetEindstatusOp(EntityManager entityManager, UUID id, OffsetDateTime tijdstip) {
        entityManager.createQuery("update Notificatie set " + Notificatie_.EINDSTATUS_OP + " = :tijdstip where "
                        + Notificatie_.ID + " = :id")
                .setParameter("tijdstip", tijdstip)
                .setParameter("id", id)
                .executeUpdate();
    }

    /** Wist het template-id, zoals bij een notificatie van vóór de takentabel. */
    public static void wisTemplateId(EntityManager entityManager, UUID id) {
        entityManager.createQuery("update Notificatie set " + Notificatie_.TEMPLATE_ID + " = null where " + Notificatie_.ID + " = :id")
                .setParameter("id", id)
                .executeUpdate();
    }

    public static void zetKekVersie(EntityManager entityManager, UUID id, int kekVersie) {
        entityManager.createQuery("update Notificatie set " + Notificatie_.KEK_VERSIE + " = :kekVersie where "
                        + Notificatie_.ID + " = :id")
                .setParameter("kekVersie", kekVersie)
                .setParameter("id", id)
                .executeUpdate();
    }

    /** Zet geldig_tot terug, zodat er geen poging meer gestart mag worden. */
    public static void verzetGeldigTot(EntityManager entityManager, UUID id, OffsetDateTime tijdstip) {
        entityManager.createQuery("update Notificatie set " + Notificatie_.GELDIG_TOT + " = :tijdstip where "
                        + Notificatie_.ID + " = :id")
                .setParameter("tijdstip", tijdstip)
                .setParameter("id", id)
                .executeUpdate();
    }

    /** De pogingen van een notificatie, op nummer. */
    public static List<Poging> pogingen(EntityManager entityManager, UUID notificatieId) {
        return entityManager.createQuery("from Poging where " + Poging_.NOTIFICATIE_ID + " = :notificatieId order by "
                        + Poging_.NUMMER, Poging.class)
                .setParameter("notificatieId", notificatieId)
                .getResultList();
    }

    public static long telPogingen(EntityManager entityManager, UUID notificatieId) {
        return entityManager.createQuery("select count(*) from Poging where " + Poging_.NOTIFICATIE_ID + " = :notificatieId",
                        Long.class)
                .setParameter("notificatieId", notificatieId)
                .getSingleResult();
    }

    /**
     * Plant in één statement {@code aantalRijen} notificaties in dezelfde status, met per notificatie
     * {@code aantalPogingen} pogingen, voor tests die meer rijen nodig hebben dan een batch groot is.
     * {@code generate_series} levert de rijen en het id wordt uit het rijnummer afgeleid, zodat de
     * pogingen naar hetzelfde id verwijzen.
     */
    public static void plantNotificaties(EntityManager entityManager, int aantalRijen, OffsetDateTime eindstatusOp,
                                         NotificatieStatus status, int aantalPogingen) {
        String idExpressie = "('00000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid";
        QuarkusTransaction.requiringNew().run(() -> {
            zetTriggerUit(entityManager);
            entityManager.createNativeQuery("INSERT INTO notificatie (id, dv_id, versie, status, eindstatus_op) "
                            + "SELECT " + idExpressie + ", ?4, 0, ?3, ?1 FROM generate_series(1, ?2) AS g")
                    .setParameter(1, eindstatusOp)
                    .setParameter(4, DV_ID)
                    .setParameter(2, aantalRijen)
                    .setParameter(3, status.name())
                    .executeUpdate();

            for (int nummer = 1; nummer <= aantalPogingen; nummer++) {
                entityManager.createNativeQuery("INSERT INTO poging (id, notificatie_id, nummer, status, notify_id, verzonden_op) "
                                + "SELECT gen_random_uuid(), " + idExpressie + ", ?3, ?4, gen_random_uuid(), ?1 "
                                + "FROM generate_series(1, ?2) AS g")
                        .setParameter(1, eindstatusOp)
                        .setParameter(2, aantalRijen)
                        .setParameter(3, nummer)
                        .setParameter(4, PogingStatus.VERZONDEN.name())
                        .executeUpdate();
            }
        });
    }

    // Alleen voor de lopende transactie; een insert met een andere status dan AANGENOMEN en zonder
    // event komt anders niet door de constraint trigger.
    private static void zetTriggerUit(EntityManager entityManager) {
        entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
    }
}
