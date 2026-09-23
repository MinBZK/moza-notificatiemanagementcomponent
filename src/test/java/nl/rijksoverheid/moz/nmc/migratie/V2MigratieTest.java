package nl.rijksoverheid.moz.nmc.migratie;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Draait de V2-backfill over bestaande V1-rijen. De gewone tests migreren een lege database, waar de
 * INSERT ... SELECT en UPDATE in V2 geen enkele rij raken. Draait op H2; PostgreSQL is hier niet getoetst.
 */
class V2MigratieTest {

    private static final OffsetDateTime AANGEMAAKT = OffsetDateTime.parse("2026-01-15T10:00:00.123456Z");

    // Eén gedeelde sessie: H2 bindt een IN-CHECK aan de sessie die de tabel aanmaakte, en die van
    // Flyway is na de migratie gesloten.
    private Connection verbinding;

    @BeforeEach
    void setUp() throws SQLException {
        verbinding = DriverManager.getConnection("jdbc:h2:mem:v2migratie-" + UUID.randomUUID(), "sa", "");
    }

    @AfterEach
    void tearDown() throws SQLException {
        verbinding.close();
    }

    @Test
    void v2_metBestaandeRijen_maaktPerNotificatieEenGeschiedenisrecordEnDeProjectie() throws SQLException {
        migreerTot("1");
        UUID aangemaakt = voegV1RijToe("CREATED");
        UUID afgeleverd = voegV1RijToe("DELIVERED");

        migreerTot("2");

        bevestigGeschiedenis(verbinding, aangemaakt, "CREATED");
        bevestigGeschiedenis(verbinding, afgeleverd, "DELIVERED");
        bevestigProjectie(verbinding, aangemaakt, "CREATED");
        bevestigProjectie(verbinding, afgeleverd, "DELIVERED");
        assertFalse(kolomBestaat(verbinding, "STATUS"), "notificatie.status hoort vervallen te zijn");
        assertFalse(kolomBestaat(verbinding, "AANGEMAAKT"), "notificatie.aangemaakt hoort vervallen te zijn");
    }

    private void migreerTot(String versie) {
        Flyway.configure()
                .dataSource(gedeeldeBron())
                .locations("classpath:db/migration")
                .target(versie)
                .load()
                .migrate();
    }

    private UUID voegV1RijToe(String status) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = verbinding.prepareStatement(
                "INSERT INTO notificatie (id, external_reference, status, aangemaakt) VALUES (?, ?, ?, ?)")) {
            insert.setObject(1, id);
            insert.setObject(2, UUID.randomUUID());
            insert.setString(3, status);
            insert.setObject(4, AANGEMAAKT);
            insert.executeUpdate();
        }

        return id;
    }

    // volgnummer 0 omdat @OrderColumn nul-gebaseerd is; een volgende registratie krijgt dan 1.
    private static void bevestigGeschiedenis(Connection verbinding, UUID id, String status) throws SQLException {
        try (PreparedStatement select = verbinding.prepareStatement(
                "SELECT volgnummer, status, tijdstip, geregistreerd FROM notificatie_status WHERE notificatie_id = ?")) {
            select.setObject(1, id);
            try (ResultSet rij = select.executeQuery()) {
                assertTrue(rij.next(), "geen geschiedenisrecord voor " + id);
                assertEquals(0, rij.getInt("volgnummer"));
                assertEquals(status, rij.getString("status"));
                assertEquals(AANGEMAAKT.toInstant(), rij.getObject("tijdstip", OffsetDateTime.class).toInstant());
                assertEquals(AANGEMAAKT.toInstant(), rij.getObject("geregistreerd", OffsetDateTime.class).toInstant());
                assertFalse(rij.next(), "precies één geschiedenisrecord verwacht voor " + id);
            }
        }
    }

    private static void bevestigProjectie(Connection verbinding, UUID id, String status) throws SQLException {
        try (PreparedStatement select = verbinding.prepareStatement(
                "SELECT laatste_status, laatste_status_update, versie FROM notificatie WHERE id = ?")) {
            select.setObject(1, id);
            try (ResultSet rij = select.executeQuery()) {
                assertTrue(rij.next());
                assertEquals(status, rij.getString("laatste_status"));
                assertEquals(AANGEMAAKT.toInstant(),
                        rij.getObject("laatste_status_update", OffsetDateTime.class).toInstant());
                assertEquals(0, rij.getLong("versie"));
            }
        }
    }

    private static boolean kolomBestaat(Connection verbinding, String kolom) throws SQLException {
        try (ResultSet kolommen = verbinding.getMetaData().getColumns(null, null, "NOTIFICATIE", kolom)) {
            return kolommen.next();
        }
    }

    // Geeft steeds de gedeelde verbinding, met een close() die niets doet.
    private DataSource gedeeldeBron() {
        Connection nietSluitend = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, methode, argumenten) -> methode.getName().equals("close")
                        ? null
                        : methode.invoke(verbinding, argumenten));

        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, methode, argumenten) -> methode.getName().equals("getConnection")
                        ? nietSluitend
                        : null);
    }
}
