package nl.rijksoverheid.moz.nmc.migratie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Draait V15 over een database met notificaties met en zonder eindstatus, events in de oude
 * default-partitie en een webhookpositie. Eigen embedded PostgreSQL, omdat de database op V14
 * stilgezet moet worden.
 */
class V15MigratieTest extends MigratieTestBasis {

    private static final UUID DV_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime LAATSTE_OVERGANG = OffsetDateTime.parse("2026-09-01T10:00:00Z");
    // V15 legt het eerste bereik van het eventlog vast op de lopende transactie-id plus deze marge.
    private static final int EVENT_MARGE = 1000;

    @Test
    void v15_vultEindstatusOpPlantWistakenEnVerplaatstHetEventlogNaarEenBereikpartitie() throws SQLException {
        String jdbcUrl = nieuweDatabase();
        migreerTot(jdbcUrl, "14", EVENT_MARGE);

        UUID eindstatus = UUID.randomUUID();
        UUID eindstatusGewist = UUID.randomUUID();
        UUID lopend = UUID.randomUUID();

        try (Connection verbinding = verbind(jdbcUrl)) {
            verbinding.setAutoCommit(false);
            voerUit(verbinding, "SET LOCAL session_replication_role = replica");
            notificatie(verbinding, eindstatus, "NIET_BEZORGBAAR", new byte[] {1, 2, 3});
            notificatie(verbinding, eindstatusGewist, "GEANNULEERD", null);
            notificatie(verbinding, lopend, "VERZONDEN", new byte[] {4, 5, 6});
            event(verbinding, eindstatus);
            event(verbinding, lopend);
            voerUit(
                    verbinding,
                    "INSERT INTO webhookpositie (dv_id, epoch, transactie_id, event_id, bijgewerkt_op) " + "VALUES ('"
                            + DV_ID + "', 1, '3', 1, '2026-09-02T10:00:00Z')");
            verbinding.commit();
        }

        migreerTot(jdbcUrl, "15", EVENT_MARGE);

        try (Connection verbinding = verbind(jdbcUrl)) {
            assertEquals(
                    LAATSTE_OVERGANG.toInstant(),
                    eindstatusOp(verbinding, eindstatus).toInstant());
            assertEquals(
                    LAATSTE_OVERGANG.toInstant(),
                    eindstatusOp(verbinding, eindstatusGewist).toInstant());
            assertNull(eindstatusOp(verbinding, lopend));
            assertEquals(
                    List.of(eindstatus + " " + LAATSTE_OVERGANG.toInstant()),
                    tekst(
                            verbinding,
                            "SELECT notificatie_id || ' ' || to_char(due AT TIME ZONE 'UTC', "
                                    + "'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM taak WHERE soort = 'WISSEN'"),
                    "alleen de notificatie met een eindstatus en een sleutel krijgt een wistaak");
            assertEquals(List.of("1"), tekst(verbinding, "SELECT count(*)::text FROM taak WHERE soort = 'ONDERHOUD'"));
            assertEquals(
                    List.of(),
                    tekst(
                            verbinding,
                            "SELECT column_name::text FROM information_schema.columns "
                                    + "WHERE table_name = 'notificatie' AND column_name LIKE 'laatste_status%'"));
            assertEquals(
                    List.of("event_0", "event_0"),
                    tekst(verbinding, "SELECT tableoid::regclass::text FROM event"),
                    "de events staan in de eerste bereikpartitie");
            assertEquals(
                    List.of("DEFAULT"),
                    tekst(
                            verbinding,
                            "SELECT pg_get_expr(c.relpartbound, c.oid) FROM pg_class c "
                                    + "WHERE c.relname = 'event_standaard'"));
            assertEquals(
                    List.of("true"),
                    tekst(
                            verbinding,
                            "SELECT (substring(pg_get_expr(c.relpartbound, c.oid) "
                                    + "FROM 'TO \\(''(\\d+)''\\)')::bigint > pg_snapshot_xmax(pg_current_snapshot())::text::bigint + 900)::text "
                                    + "FROM pg_class c WHERE c.relname = 'event_0'"),
                    "event_0 loopt tot boven de uitgedeelde transactie-ids plus de marge");
            assertEquals(
                    List.of("CREATE INDEX taak_notificatie_idx ON ONLY public.taak USING btree (notificatie_id)"),
                    tekst(
                            verbinding,
                            "SELECT indexdef FROM pg_indexes WHERE tablename = 'taak' AND indexname = 'taak_notificatie_idx'"),
                    "de cascade bij het verwijderen van een notificatie zoekt taken op notificatie_id alleen");
            assertEquals(
                    List.of("2026-09-02 10:00:00+00"),
                    tekst(
                            verbinding,
                            "SELECT to_char(geleverd_op AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') || '+00' FROM webhookpositie"));
        }

        // De rolinstellingen gelden voor een nieuwe sessie.
        try (Connection verbinding = verbind(jdbcUrl)) {
            assertEquals(List.of("30s"), tekst(verbinding, "SHOW statement_timeout"));
            assertEquals(List.of("1min"), tekst(verbinding, "SHOW transaction_timeout"));
            assertEquals(List.of("30s"), tekst(verbinding, "SHOW idle_in_transaction_session_timeout"));
        }
    }

    private static void notificatie(Connection verbinding, UUID id, String status, byte[] sleutel) throws SQLException {
        try (PreparedStatement insert = verbinding.prepareStatement(
                "INSERT INTO notificatie "
                        + "(id, dv_id, versie, status, laatste_status_update, sleutel_gewrapt, kek_versie) VALUES (?, ?, 3, ?, ?, ?, 1)")) {
            insert.setObject(1, id);
            insert.setObject(2, DV_ID);
            insert.setString(3, status);
            insert.setObject(4, LAATSTE_OVERGANG);
            insert.setBytes(5, sleutel);
            insert.executeUpdate();
        }
    }

    private static void event(Connection verbinding, UUID notificatieId) throws SQLException {
        try (PreparedStatement insert = verbinding.prepareStatement("INSERT INTO event "
                + "(geregistreerd, dv_id, notificatie_id, volgnummer, naar) VALUES (?, ?, ?, 0, 'AANGENOMEN')")) {
            insert.setObject(1, LAATSTE_OVERGANG);
            insert.setObject(2, DV_ID);
            insert.setObject(3, notificatieId);
            insert.executeUpdate();
        }
    }

    private static OffsetDateTime eindstatusOp(Connection verbinding, UUID id) throws SQLException {
        try (PreparedStatement select =
                verbinding.prepareStatement("SELECT eindstatus_op FROM notificatie WHERE id = ?")) {
            select.setObject(1, id);

            try (ResultSet rij = select.executeQuery()) {
                rij.next();

                return rij.getObject(1, OffsetDateTime.class);
            }
        }
    }

    private static List<String> tekst(Connection verbinding, String sql) throws SQLException {
        List<String> waarden = new ArrayList<>();

        try (var statement = verbinding.createStatement();
                ResultSet rij = statement.executeQuery(sql)) {
            while (rij.next()) {
                waarden.add(rij.getString(1));
            }
        }

        return waarden;
    }

    private static void voerUit(Connection verbinding, String sql) throws SQLException {
        try (var statement = verbinding.createStatement()) {
            statement.execute(sql);
        }
    }
}
