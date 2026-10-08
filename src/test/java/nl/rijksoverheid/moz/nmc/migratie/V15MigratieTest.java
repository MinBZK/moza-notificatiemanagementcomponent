package nl.rijksoverheid.moz.nmc.migratie;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Draait V15 over een database waarin de controletaak vaststeltaken plande voordat de termijn bestond.
 * Eigen embedded PostgreSQL, omdat de database op V14 stilgezet moet worden.
 */
class V15MigratieTest {

    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "postgres";
    private static final UUID DV_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime BEZORGD_OP = OffsetDateTime.parse("2026-09-01T10:00:00Z");

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws IOException {
        postgres = EmbeddedPostgres.start();
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    void v15_vultBezorgdOpEnVerwijdertOpenVaststeltaken() throws SQLException {
        String jdbcUrl = nieuweDatabase();
        migreerTot(jdbcUrl, "14");

        UUID bezorgd = UUID.randomUUID();
        UUID verzonden = UUID.randomUUID();

        try (Connection verbinding = DriverManager.getConnection(jdbcUrl, DB_USER, DB_PASSWORD)) {
            verbinding.setAutoCommit(false);
            voerUit(verbinding, "SET LOCAL session_replication_role = replica");
            notificatie(verbinding, bezorgd, "BEZORGD");
            notificatie(verbinding, verzonden, "VERZONDEN");
            poging(verbinding, bezorgd, "BEZORGD", BEZORGD_OP);
            poging(verbinding, verzonden, "VERZONDEN", null);
            taak(verbinding, "BEZORGING_VASTSTELLEN", bezorgd);
            taak(verbinding, "RECONCILIEREN", verzonden);
            verbinding.commit();
        }

        migreerTot(jdbcUrl, "15");

        try (Connection verbinding = DriverManager.getConnection(jdbcUrl, DB_USER, DB_PASSWORD)) {
            assertEquals(BEZORGD_OP.toInstant(), bezorgdOp(verbinding, bezorgd).toInstant());
            assertNull(bezorgdOp(verbinding, verzonden));
            assertEquals(0, aantalTaken(verbinding, "BEZORGING_VASTSTELLEN"));
            assertEquals(1, aantalTaken(verbinding, "RECONCILIEREN"), "andere taken blijven staan");
        }
    }

    private static String nieuweDatabase() throws SQLException {
        String database = "v15migratie_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection beheer = DriverManager.getConnection(
                "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres", DB_USER, DB_PASSWORD);
             var statement = beheer.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }

        return "jdbc:postgresql://localhost:" + postgres.getPort() + "/" + database;
    }

    private static void migreerTot(String jdbcUrl, String versie) {
        Flyway.configure()
                .dataSource(jdbcUrl, DB_USER, DB_PASSWORD)
                .locations("classpath:db/migration")
                .target(versie)
                .load()
                .migrate();
    }

    private static void notificatie(Connection verbinding, UUID id, String status) throws SQLException {
        try (PreparedStatement insert = verbinding.prepareStatement(
                "INSERT INTO notificatie (id, dv_id, versie, status, laatste_status_update) VALUES (?, ?, 3, ?, now())")) {
            insert.setObject(1, id);
            insert.setObject(2, DV_ID);
            insert.setString(3, status);
            insert.executeUpdate();
        }
    }

    private static void poging(Connection verbinding, UUID notificatieId, String status, OffsetDateTime receipt)
            throws SQLException {
        try (PreparedStatement insert = verbinding.prepareStatement("INSERT INTO poging "
                + "(id, notificatie_id, nummer, status, notify_id, verzonden_op, receipt_tijdstip) VALUES (?, ?, 1, ?, ?, ?, ?)")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, notificatieId);
            insert.setString(3, status);
            insert.setObject(4, UUID.randomUUID());
            insert.setObject(5, BEZORGD_OP.minusMinutes(1));
            insert.setObject(6, receipt);
            insert.executeUpdate();
        }
    }

    private static void taak(Connection verbinding, String soort, UUID notificatieId) throws SQLException {
        try (PreparedStatement insert = verbinding.prepareStatement(
                "INSERT INTO taak (soort, dv_id, notificatie_id, due, status) VALUES (?, ?, ?, now(), 'OPEN')")) {
            insert.setString(1, soort);
            insert.setObject(2, DV_ID);
            insert.setObject(3, notificatieId);
            insert.executeUpdate();
        }
    }

    private static OffsetDateTime bezorgdOp(Connection verbinding, UUID notificatieId) throws SQLException {
        try (PreparedStatement select = verbinding.prepareStatement("SELECT bezorgd_op FROM poging WHERE notificatie_id = ?")) {
            select.setObject(1, notificatieId);

            try (ResultSet rij = select.executeQuery()) {
                rij.next();

                return rij.getObject(1, OffsetDateTime.class);
            }
        }
    }

    private static int aantalTaken(Connection verbinding, String soort) throws SQLException {
        try (PreparedStatement select = verbinding.prepareStatement("SELECT COUNT(*) FROM taak WHERE soort = ?")) {
            select.setString(1, soort);

            try (ResultSet rij = select.executeQuery()) {
                rij.next();

                return rij.getInt(1);
            }
        }
    }

    private static void voerUit(Connection verbinding, String sql) throws SQLException {
        try (var statement = verbinding.createStatement()) {
            statement.execute(sql);
        }
    }
}
