package nl.rijksoverheid.moz.nmc.migratie;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Draait de V5-backfill over rijen zoals het oude schrijfpad ze achterliet: een projectie in
 * {@code laatste_status}, een {@code external_reference} en de geschiedenis in
 * {@code notificatie_status}. Eigen embedded PostgreSQL, omdat de database op V4 stilgezet moet worden.
 */
class V5MigratieTest {

    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "postgres";
    private static final OffsetDateTime AANGEMAAKT = OffsetDateTime.parse("2026-01-15T10:00:00Z");

    private static EmbeddedPostgres postgres;

    private String jdbcUrl;
    private Connection verbinding;

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

    @BeforeEach
    void setUp() throws SQLException {
        String database = "v5migratie_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection beheer = DriverManager.getConnection(
                "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres", DB_USER, DB_PASSWORD);
             var statement = beheer.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }

        jdbcUrl = "jdbc:postgresql://localhost:" + postgres.getPort() + "/" + database;
        verbinding = DriverManager.getConnection(jdbcUrl, DB_USER, DB_PASSWORD);
    }

    @AfterEach
    void tearDown() throws SQLException {
        verbinding.close();
    }

    @Test
    void v5_bezorgdeNotificatie_krijgtStatusPogingEnEvents() throws SQLException {
        migreerTot("4");
        UUID notifyId = UUID.randomUUID();
        UUID id = oudeNotificatie(notifyId, "DELIVERED", 2, "CREATED", "SENDING", "DELIVERED");

        migreerTot("5");

        assertEquals(Arrays.asList("BEZORGD", null, "2"), notificatie(id));
        assertEquals(List.of("BEZORGD", notifyId.toString()), poging(id));
        assertEquals(List.of("0:null>AANGENOMEN", "1:AANGENOMEN>VERZONDEN", "2:VERZONDEN>BEZORGD"), events(id));
    }

    // A, B, A: het oude model legde een terugkerende status opnieuw vast. De events nemen die
    // geschiedenis over zoals hij was; de laatste status wordt de huidige.
    @Test
    void v5_terugkerendeStatus_wordtOvergenomenAlsGeschiedenis() throws SQLException {
        migreerTot("4");
        UUID id = oudeNotificatie(UUID.randomUUID(), "PERMANENT_FAILURE", 4,
                "CREATED", "SENDING", "PERMANENT_FAILURE", "DELIVERED", "PERMANENT_FAILURE");

        migreerTot("5");

        assertEquals(List.of("NIET_BEZORGBAAR", "ONBEREIKBAAR", "4"), notificatie(id));
        assertEquals(5, events(id).size());
    }

    @Test
    void v5_notificatieZonderVerzending_krijgtGeenPoging() throws SQLException {
        migreerTot("4");
        UUID id = oudeNotificatie(null, "CREATED", 0, "CREATED");

        migreerTot("5");

        assertEquals(Arrays.asList("AANGENOMEN", null, "0"), notificatie(id));
        assertNull(poging(id));
    }

    // Een onbekende status verandert in het nieuwe model niets; de status komt uit het laatste record
    // met een bekende status, en het ONBEKEND-record wordt geen event.
    @Test
    void v5_onbekendNaVerzending_blijftVerzonden() throws SQLException {
        migreerTot("4");
        UUID id = oudeNotificatie(UUID.randomUUID(), "ONBEKEND", 2, "CREATED", "SENDING", "ONBEKEND");

        migreerTot("5");

        assertEquals(Arrays.asList("VERZONDEN", null, "1"), notificatie(id));
        assertEquals("VERZONDEN", poging(id).getFirst());
        assertEquals(List.of("0:null>AANGENOMEN", "1:AANGENOMEN>VERZONDEN"), events(id));
    }

    @Test
    void v5_onbekendNaBezorging_blijftBezorgd() throws SQLException {
        migreerTot("4");
        UUID id = oudeNotificatie(UUID.randomUUID(), "ONBEKEND", 3, "CREATED", "SENDING", "DELIVERED", "ONBEKEND");

        migreerTot("5");

        assertEquals(Arrays.asList("BEZORGD", null, "2"), notificatie(id));
        assertEquals("BEZORGD", poging(id).getFirst());
        assertEquals(3, events(id).size());
    }

    // Na de backfill bewaakt de trigger het schrijfpad; een versie zonder event wordt geweigerd.
    @Test
    void v5_naDeBackfill_weigertDeTriggerEenVersieZonderEvent() throws SQLException {
        migreerTot("4");
        UUID id = oudeNotificatie(UUID.randomUUID(), "SENDING", 1, "CREATED", "SENDING");
        migreerTot("5");

        assertThrows(SQLException.class, () -> {
            try (PreparedStatement update = verbinding.prepareStatement(
                    "UPDATE notificatie SET versie = 2, status = 'BEZORGD' WHERE id = ?")) {
                update.setObject(1, id);
                update.executeUpdate();
            }
        });
        assertFalse(events(id).isEmpty());
    }

    private void migreerTot(String versie) {
        Flyway.configure()
                .dataSource(jdbcUrl, DB_USER, DB_PASSWORD)
                .locations("classpath:db/migration")
                .target(versie)
                .load()
                .migrate();
    }

    // Een rij zoals het oude schrijfpad hem achterliet, met één geschiedenisrecord per status.
    private UUID oudeNotificatie(UUID notifyId, String laatsteStatus, long versie, String... geschiedenis)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = verbinding.prepareStatement(
                "INSERT INTO notificatie (id, versie, external_reference, laatste_status, laatste_status_update) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            insert.setObject(1, id);
            insert.setLong(2, versie);
            insert.setObject(3, notifyId);
            insert.setString(4, laatsteStatus);
            insert.setObject(5, AANGEMAAKT);
            insert.executeUpdate();
        }

        for (int volgnummer = 0; volgnummer < geschiedenis.length; volgnummer++) {
            try (PreparedStatement insert = verbinding.prepareStatement(
                    "INSERT INTO notificatie_status (notificatie_id, volgnummer, status, tijdstip, geregistreerd) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                insert.setObject(1, id);
                insert.setInt(2, volgnummer);
                insert.setString(3, geschiedenis[volgnummer]);
                insert.setObject(4, AANGEMAAKT.plusMinutes(volgnummer));
                insert.setObject(5, AANGEMAAKT.plusMinutes(volgnummer));
                insert.executeUpdate();
            }
        }

        return id;
    }

    private List<String> notificatie(UUID id) throws SQLException {
        try (PreparedStatement select = verbinding.prepareStatement(
                "SELECT status, reden, versie FROM notificatie WHERE id = ?")) {
            select.setObject(1, id);
            try (ResultSet rij = select.executeQuery()) {
                assertTrue(rij.next());
                List<String> waarden = new ArrayList<>();
                waarden.add(rij.getString("status"));
                waarden.add(rij.getString("reden"));
                waarden.add(String.valueOf(rij.getLong("versie")));

                return waarden;
            }
        }
    }

    private List<String> poging(UUID id) throws SQLException {
        try (PreparedStatement select = verbinding.prepareStatement(
                "SELECT status, notify_id FROM poging WHERE notificatie_id = ?")) {
            select.setObject(1, id);
            try (ResultSet rij = select.executeQuery()) {
                if (!rij.next()) {
                    return null;
                }

                return List.of(rij.getString("status"), rij.getString("notify_id"));
            }
        }
    }

    private List<String> events(UUID id) throws SQLException {
        try (PreparedStatement select = verbinding.prepareStatement(
                "SELECT volgnummer, van, naar FROM event WHERE notificatie_id = ? ORDER BY volgnummer")) {
            select.setObject(1, id);
            try (ResultSet rij = select.executeQuery()) {
                List<String> events = new ArrayList<>();

                while (rij.next()) {
                    events.add(rij.getLong("volgnummer") + ":" + rij.getString("van") + ">" + rij.getString("naar"));
                }

                return events;
            }
        }
    }
}
