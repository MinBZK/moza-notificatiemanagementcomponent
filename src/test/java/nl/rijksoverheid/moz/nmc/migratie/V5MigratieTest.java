package nl.rijksoverheid.moz.nmc.migratie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
class V5MigratieTest extends MigratieTestBasis {

    private static final OffsetDateTime AANGEMAAKT = OffsetDateTime.parse("2026-01-15T10:00:00Z");
    // Later dan elk geschiedenisrecord, zodat te zien is uit welke bron een gemigreerd tijdstip komt.
    private static final OffsetDateTime LAATSTE_UPDATE = AANGEMAAKT.plusHours(1);
    private static final UUID GECONFIGUREERDE_DV = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private String jdbcUrl;
    private Connection verbinding;

    @BeforeEach
    void setUp() throws SQLException {
        jdbcUrl = nieuweDatabase();
        verbinding = verbind(jdbcUrl);
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

    // Elke oude status met haar geschiedenis. De poging krijgt de status uit de mapping, of verzonden als de
    // mapping er geen heeft (CREATED met een referentie van NotifyNL); verzonden_op komt uit het eerste
    // SENDING-record en receipt_tijdstip uit het laatste record met een uitkomst.
    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "-", value = {
            "CREATED,           CREATED,                           AANGENOMEN,        -,            VERZONDEN",
            "SENDING,           CREATED|SENDING,                   VERZONDEN,         -,            VERZONDEN",
            "DELIVERED,         CREATED|SENDING|DELIVERED,         BEZORGD,           -,            BEZORGD",
            "PERMANENT_FAILURE, CREATED|SENDING|PERMANENT_FAILURE, NIET_BEZORGBAAR,   ONBEREIKBAAR, PERMANENT_MISLUKT",
            "TEMPORARY_FAILURE, CREATED|SENDING|TEMPORARY_FAILURE, NIET_BEZORGBAAR,   ONBEREIKBAAR, TIJDELIJK_MISLUKT",
            "TECHNICAL_FAILURE, CREATED|SENDING|TECHNICAL_FAILURE, TECHNISCH_MISLUKT, TECHNISCH,    TECHNISCH_MISLUKT"})
    void v5_elkeOudeStatus_krijgtStatusRedenEnPogingUitDeMapping(String oud, String geschiedenis, String status,
                                                               String reden, String pogingStatus) throws SQLException {
        String[] records = geschiedenis.split("\\|");
        migreerTot("4");
        UUID notifyId = UUID.randomUUID();
        UUID id = oudeNotificatie(notifyId, oud, records.length - 1, records);

        migreerTot("5");

        assertEquals(Arrays.asList(status, reden, String.valueOf(records.length - 1)), notificatie(id));
        assertEquals(List.of(pogingStatus, notifyId.toString()), poging(id));
        assertEquals(Arrays.asList(records.length > 1 ? AANGEMAAKT.plusMinutes(1).toInstant() : null,
                records.length > 2 ? AANGEMAAKT.plusMinutes(2).toInstant() : null), pogingTijdstippen(id));
        assertEquals(records.length, events(id).size());
    }

    // Het vangnet: een rij zonder geschiedenisrecord met een bekende status krijgt haar status uit
    // laatste_status en houdt haar versie. Zo'n rij krijgt geen events.
    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
            "PERMANENT_FAILURE, NIET_BEZORGBAAR,       ONBEREIKBAAR",
            "TECHNICAL_FAILURE, TECHNISCH_MISLUKT,     TECHNISCH",
            "ONBEKEND,          BEZORGSTATUS_ONBEKEND, -"})
    void v5_rijZonderBruikbareGeschiedenis_krijgtDeStatusUitLaatsteStatus(String oud, String status, String reden)
            throws SQLException {
        migreerTot("4");
        UUID id = oudeNotificatie(null, oud, 3);

        migreerTot("5");

        assertEquals(Arrays.asList(status, reden, "3"), notificatie(id));
        assertNull(poging(id));
        assertEquals(List.of(), events(id));
    }

    // V8 geeft bestaande rijen en hun events de ene geconfigureerde dienstverlener.
    @Test
    void v8_bestaandeRijenEnEvents_krijgenDeGeconfigureerdeDienstverlener() throws SQLException {
        migreerTot("4");
        UUID id = oudeNotificatie(UUID.randomUUID(), "DELIVERED", 2, "CREATED", "SENDING", "DELIVERED");

        migreerTot("8");

        assertEquals(List.of(GECONFIGUREERDE_DV), kolom("SELECT dv_id FROM notificatie WHERE id = ?", id, UUID.class));
        assertEquals(List.of(GECONFIGUREERDE_DV, GECONFIGUREERDE_DV, GECONFIGUREERDE_DV),
                kolom("SELECT dv_id FROM event WHERE notificatie_id = ? ORDER BY volgnummer", id, UUID.class));
    }

    // aangenomen_op stuurt het dagquotum en de controletaak. Het komt uit het eerste event, en voor een
    // rij zonder events uit laatste_status_update.
    @Test
    void v9_aangenomenOp_komtUitHetEersteEventEnAndersUitDeLaatsteStatusUpdate() throws SQLException {
        migreerTot("4");
        UUID metEvents = oudeNotificatie(UUID.randomUUID(), "DELIVERED", 2, "CREATED", "SENDING", "DELIVERED");
        UUID zonderEvents = oudeNotificatie(null, "PERMANENT_FAILURE", 0);

        migreerTot("9");

        assertEquals(List.of(AANGEMAAKT.toInstant()), aangenomenOp(metEvents));
        assertEquals(List.of(LAATSTE_UPDATE.toInstant()), aangenomenOp(zonderEvents));
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

    // V6 ruimt het oude model op nadat V5 het heeft overgezet; de gemigreerde gegevens blijven staan.
    @Test
    void v6_naDeBackfill_verwijdertOudeGeschiedenisEnReferentie() throws SQLException {
        migreerTot("4");
        UUID notifyId = UUID.randomUUID();
        UUID id = oudeNotificatie(notifyId, "DELIVERED", 2, "CREATED", "SENDING", "DELIVERED");

        migreerTot("6");

        try (ResultSet tabellen = verbinding.getMetaData().getTables(null, null, "notificatie_status", null);
             ResultSet kolommen = verbinding.getMetaData().getColumns(null, null, "notificatie", "external_reference")) {
            assertFalse(tabellen.next(), "notificatie_status hoort vervallen te zijn");
            assertFalse(kolommen.next(), "notificatie.external_reference hoort vervallen te zijn");
        }

        assertEquals(List.of("BEZORGD", notifyId.toString()), poging(id));
        assertEquals(3, events(id).size());
    }

    // Productie heeft rijen uit het oude model; elke latere migratie moet daarover heen kunnen.
    @Test
    void alleMigraties_overOudeRijen_slagen() throws SQLException {
        migreerTot("4");
        UUID bezorgd = oudeNotificatie(UUID.randomUUID(), "DELIVERED", 2, "CREATED", "SENDING", "DELIVERED");
        UUID onderweg = oudeNotificatie(UUID.randomUUID(), "SENDING", 1, "CREATED", "SENDING");

        migreerTot("latest");

        assertEquals("BEZORGD", notificatie(bezorgd).getFirst());
        assertEquals("VERZONDEN", notificatie(onderweg).getFirst());
    }

    private void migreerTot(String versie) {
        migreerTot(jdbcUrl, versie);
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
            insert.setObject(5, LAATSTE_UPDATE);
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

    // verzonden_op en receipt_tijdstip van de poging; null waar de kolom leeg is.
    private List<Instant> pogingTijdstippen(UUID id) throws SQLException {
        try (PreparedStatement select = verbinding.prepareStatement(
                "SELECT verzonden_op, receipt_tijdstip FROM poging WHERE notificatie_id = ?")) {
            select.setObject(1, id);
            try (ResultSet rij = select.executeQuery()) {
                assertTrue(rij.next());

                return Arrays.asList(instant(rij, "verzonden_op"), instant(rij, "receipt_tijdstip"));
            }
        }
    }

    private List<Instant> aangenomenOp(UUID id) throws SQLException {
        return kolom("SELECT aangenomen_op FROM notificatie WHERE id = ?", id, OffsetDateTime.class).stream()
                .map(OffsetDateTime::toInstant).toList();
    }

    private static Instant instant(ResultSet rij, String kolom) throws SQLException {
        OffsetDateTime waarde = rij.getObject(kolom, OffsetDateTime.class);

        return waarde == null ? null : waarde.toInstant();
    }

    private <T> List<T> kolom(String sql, UUID id, Class<T> type) throws SQLException {
        try (PreparedStatement select = verbinding.prepareStatement(sql)) {
            select.setObject(1, id);
            try (ResultSet rij = select.executeQuery()) {
                List<T> waarden = new ArrayList<>();

                while (rij.next()) {
                    waarden.add(rij.getObject(1, type));
                }

                return waarden;
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
