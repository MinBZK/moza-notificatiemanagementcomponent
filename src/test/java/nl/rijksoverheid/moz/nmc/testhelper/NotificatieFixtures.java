package nl.rijksoverheid.moz.nmc.testhelper;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Schrijft rijen buiten Hibernate en buiten de overgangsfunctie om, voor tests die een toestand
 * willen die de applicatie zelf niet maakt: een willekeurige status met een terug- of
 * vooruitgedateerde registratietijd. Staat op één plek zodat een kolomwijziging niet door meerdere
 * testklassen hoeft.
 * <p>
 * Elke methode verwacht een lopende transactie. De constraint trigger op {@code notificatie} eist een
 * event per versie; de inserts hier zetten hem voor de eigen transactie uit.
 */
public final class NotificatieFixtures {

    /** De dienstverlener uit migratie V8 en {@code nmc.dienstverlener.id} onder {@code %test}. */
    public static final UUID DV_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private NotificatieFixtures() {
    }

    /** Een notificatie in de gegeven status, zonder poging. */
    public static void voegNotificatieToe(EntityManager entityManager, UUID id, String callbackUrl,
                                          NotificatieStatus status, OffsetDateTime laatsteStatusUpdate) {
        zetTriggerUit(entityManager);
        entityManager.createNativeQuery("INSERT INTO notificatie (id, dv_id, versie, callback_url, status, laatste_status_update) "
                        + "VALUES (?1, ?5, 0, ?2, ?3, ?4)")
                .setParameter(1, id)
                .setParameter(5, DV_ID)
                .setParameter(2, callbackUrl)
                .setParameter(3, status.name())
                .setParameter(4, laatsteStatusUpdate)
                .executeUpdate();
    }

    /** Een poging bij een bestaande notificatie, met het NotifyNL-id waarop een receipt binnenkomt. */
    public static void voegPogingToe(EntityManager entityManager, UUID notificatieId, int nummer, UUID notifyId,
                                     PogingStatus status, OffsetDateTime verzondenOp) {
        entityManager.createNativeQuery("INSERT INTO poging (id, notificatie_id, nummer, status, notify_id, verzonden_op) "
                        + "VALUES (?1, ?2, ?3, ?4, ?5, ?6)")
                .setParameter(1, UUID.randomUUID())
                .setParameter(2, notificatieId)
                .setParameter(3, nummer)
                .setParameter(4, status.name())
                .setParameter(5, notifyId)
                .setParameter(6, verzondenOp)
                .executeUpdate();
    }

    /** Een event met een eigen registratietijd, los van {@code laatste_status_update} op de notificatie. */
    public static void voegEventToe(EntityManager entityManager, UUID notificatieId, long volgnummer,
                                    NotificatieStatus van, NotificatieStatus naar, OffsetDateTime geregistreerd) {
        entityManager.createNativeQuery("INSERT INTO event (geregistreerd, dv_id, notificatie_id, volgnummer, van, naar) "
                        + "VALUES (?1, ?6, ?2, ?3, ?4, ?5)")
                .setParameter(1, geregistreerd)
                .setParameter(6, DV_ID)
                .setParameter(2, notificatieId)
                .setParameter(3, volgnummer)
                .setParameter(4, van != null ? van.name() : null)
                .setParameter(5, naar.name())
                .executeUpdate();
    }

    public static void verwijderNotificatie(EntityManager entityManager, UUID id) {
        entityManager.createNativeQuery("DELETE FROM notificatie WHERE id = ?1")
                .setParameter(1, id)
                .executeUpdate();
    }

    /** Het aantal transacties op deze database dat op een vergrendeling wacht. */
    public static long telTransactiesWachtendOpLock(EntityManager entityManager) {
        // Native: de systeemcatalogus.
        return ((Number) entityManager.createNativeQuery("SELECT count(*) FROM pg_stat_activity "
                + "WHERE datname = current_database() AND wait_event_type = 'Lock'").getSingleResult()).longValue();
    }

    /** Zet de status rechtstreeks, zonder overgang en zonder versieverhoging; de trigger staat daarvoor uit. */
    public static void zetStatus(EntityManager entityManager, UUID id, NotificatieStatus status) {
        zetTriggerUit(entityManager);
        entityManager.createNativeQuery("UPDATE notificatie SET status = ?1 WHERE id = ?2")
                .setParameter(1, status.name())
                .setParameter(2, id)
                .executeUpdate();
    }

    /** De status van de notificatie met een poging op {@code GEPLAND}, rechtstreeks uit de database. */
    public static String statusBijGeplandePoging(EntityManager entityManager) {
        return (String) entityManager.createNativeQuery("SELECT n.status FROM notificatie n "
                        + "JOIN poging p ON p.notificatie_id = n.id WHERE p.status = 'GEPLAND'")
                .getSingleResult();
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

    /** Zet de registratietijd terug, zodat een notificatie buiten de bewaartermijn valt. */
    public static void verzetLaatsteStatusUpdate(EntityManager entityManager, UUID id, OffsetDateTime tijdstip) {
        entityManager.createNativeQuery("UPDATE notificatie SET laatste_status_update = ?1 WHERE id = ?2")
                .setParameter(1, tijdstip)
                .setParameter(2, id)
                .executeUpdate();
    }

    public static long telPogingen(EntityManager entityManager, UUID notificatieId) {
        return ((Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM poging WHERE notificatie_id = ?1")
                .setParameter(1, notificatieId)
                .getSingleResult()).longValue();
    }

    /**
     * Plant in één statement {@code aantalRijen} notificaties in dezelfde status, met per notificatie
     * {@code aantalPogingen} pogingen, voor tests die meer rijen nodig hebben dan een batch groot is.
     * {@code generate_series} levert de rijen en het id wordt uit het rijnummer afgeleid, zodat de
     * pogingen naar hetzelfde id verwijzen.
     */
    public static void plantNotificaties(EntityManager entityManager, int aantalRijen, OffsetDateTime tijdstip,
                                         NotificatieStatus status, int aantalPogingen) {
        String idExpressie = "('00000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid";
        QuarkusTransaction.requiringNew().run(() -> {
            zetTriggerUit(entityManager);
            entityManager.createNativeQuery("INSERT INTO notificatie (id, dv_id, versie, status, laatste_status_update) "
                            + "SELECT " + idExpressie + ", ?4, 0, ?3, ?1 FROM generate_series(1, ?2) AS g")
                    .setParameter(1, tijdstip)
                    .setParameter(4, DV_ID)
                    .setParameter(2, aantalRijen)
                    .setParameter(3, status.name())
                    .executeUpdate();

            for (int nummer = 1; nummer <= aantalPogingen; nummer++) {
                entityManager.createNativeQuery("INSERT INTO poging (id, notificatie_id, nummer, status, notify_id, verzonden_op) "
                                + "SELECT gen_random_uuid(), " + idExpressie + ", ?3, ?4, gen_random_uuid(), ?1 "
                                + "FROM generate_series(1, ?2) AS g")
                        .setParameter(1, tijdstip)
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
