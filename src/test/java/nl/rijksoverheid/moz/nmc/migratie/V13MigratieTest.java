package nl.rijksoverheid.moz.nmc.migratie;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Draait V13 over een database waarin de controletaak vaststeltaken plande voordat de termijn bestond.
 * Eigen embedded PostgreSQL, omdat de database op V12 stilgezet moet worden.
 */
class V13MigratieTest extends MigratieTestBasis {

    private static final UUID DV_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime BEZORGD_OP = OffsetDateTime.parse("2026-09-01T10:00:00Z");

    @Test
    void v13_vultBezorgdOpEnVerwijdertOpenVaststeltaken() throws SQLException {
        String jdbcUrl = nieuweDatabase();
        migreerTot(jdbcUrl, "12");

        UUID bezorgd = UUID.randomUUID();
        UUID verzonden = UUID.randomUUID();

        try (Connection verbinding = verbind(jdbcUrl)) {
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

        migreerTot(jdbcUrl, "13");

        try (Connection verbinding = verbind(jdbcUrl)) {
            assertEquals(BEZORGD_OP.toInstant(), bezorgdOp(verbinding, bezorgd).toInstant());
            assertNull(bezorgdOp(verbinding, verzonden));
            assertEquals(0, aantalTaken(verbinding, "BEZORGING_VASTSTELLEN"));
            assertEquals(1, aantalTaken(verbinding, "RECONCILIEREN"), "andere taken blijven staan");
        }
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
