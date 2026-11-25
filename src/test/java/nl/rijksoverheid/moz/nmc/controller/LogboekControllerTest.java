package nl.rijksoverheid.moz.nmc.controller;

import io.quarkiverse.httpproblem.HttpProblem;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext;
import nl.rijksoverheid.moz.nmc.helper.HashHelper;
import nl.rijksoverheid.moz.nmc.repository.LogboekLezer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * De inzage tegen een echte PostgreSQL met de tabel zoals de LDV-wrapper die aanmaakt: de zoekvraag per
 * betrokkene (hash en type), de leesregel en de periode.
 */
@QuarkusTest
class LogboekControllerTest {

    private static final String PAD = "/api/nmc/v1/logboek/verwerkingen";
    private static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");

    @Inject
    HashHelper hashHelper;

    @ConfigProperty(name = "logboekdataverwerking.postgresql.url")
    String url;

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "postgres", "postgres"); Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS logboek_spans (
                        trace_id text NOT NULL, span_id text NOT NULL, status text NOT NULL, "name" text NOT NULL,
                        start_time bigint NOT NULL, end_time bigint NOT NULL, parent_span_id text,
                        attributes jsonb, resource jsonb, PRIMARY KEY (trace_id, span_id))""");
            s.execute("DELETE FROM logboek_spans");
        }
    }

    @Test
    void inzage_perBsn_geeftAlleenDeVerwerkingenVanDieBetrokkeneNieuwsteEerst() throws SQLException {
        voegToe("t1", "s1", null, "UNSET", "notificatieAannemen", T0, "999990020", "BSN", null);
        voegToe("t2", "s1", null, "ERROR", "notificatieAannemen", T0.plusSeconds(60), "999990020", "BSN",
                "java.lang.IllegalStateException");
        voegToe("t3", "s1", null, "UNSET", "notificatieAannemen", T0, "111222333", "BSN", null);
        // Zelfde nummer, ander type: hoort niet bij deze betrokkene.
        voegToe("t4", "s1", null, "UNSET", "notificatieAannemen", T0, "999990020", "RSIN", null);

        given().header("X-Betrokkene", "BSN:999990020")
                .when().get(PAD)
                .then()
                .statusCode(200)
                .body("betrokkene.type", equalTo("BSN"))
                .body("betrokkene.waarde", equalTo("999990020"))
                .body("verwerkingen", hasSize(2))
                .body("verwerkingen[0].uitkomst", equalTo("mislukt"))
                .body("verwerkingen[0].foutcategorie", equalTo("java.lang.IllegalStateException"))
                .body("verwerkingen[1].uitkomst", equalTo("geslaagd"))
                .body("verwerkingen[1].handeling", equalTo("notificatieAannemen"))
                .body("verwerkingen[1].verwerkingsactiviteit", equalTo("https://mijnoverheidzakelijk.nl/verwerkingsactiviteiten/TODO-NMC"))
                .body("verwerkingen[1].tijdstip", equalTo("2026-11-25T10:00:00Z"));
    }

    @Test
    void inzage_perEmail_vindtDeDecentraleAanname() throws SQLException {
        voegToe("t1", "s1", null, "UNSET", "decentraleNotificatieAannemen", T0, "iemand@example.nl", "EMAIL", null);

        given().header("X-Betrokkene", "EMAIL:iemand@example.nl")
                .when().get(PAD)
                .then()
                .statusCode(200)
                .body("verwerkingen", hasSize(1))
                .body("verwerkingen[0].handeling", equalTo("decentraleNotificatieAannemen"));
    }

    @Test
    void inzage_metPeriode_laatAlleenVerwerkingenBinnenVanTotZien() throws SQLException {
        voegToe("t1", "s1", null, "UNSET", "notificatieAannemen", T0, "999990020", "BSN", null);
        voegToe("t2", "s1", null, "UNSET", "notificatieAannemen", T0.plusSeconds(3600), "999990020", "BSN", null);

        given().header("X-Betrokkene", "BSN:999990020")
                .queryParam("van", "2026-11-25T10:30:00Z")
                .queryParam("tot", "2026-11-25T12:00:00Z")
                .when().get(PAD)
                .then()
                .statusCode(200)
                .body("verwerkingen", hasSize(1))
                .body("verwerkingen[0].tijdstip", equalTo("2026-11-25T11:00:00Z"));
    }

    @Test
    void inzage_zonderVerwerkingen_geeftEenLegeLijst() {
        given().header("X-Betrokkene", "KVK:68750110")
                .when().get(PAD)
                .then()
                .statusCode(200)
                .body("verwerkingen", hasSize(0));
    }

    @Test
    void inzage_metOngeldigeBetrokkene_geeft400() {
        given().header("X-Betrokkene", "BSN:12345")
                .when().get(PAD)
                .then()
                .statusCode(400);
    }

    @Test
    void inzage_zonderBetrokkene_geeft400() {
        given().when().get(PAD).then().statusCode(400);
    }

    @Test
    void inzage_metVanNaTot_geeft400() {
        given().header("X-Betrokkene", "BSN:999990020")
                .queryParam("van", "2026-11-25T12:00:00Z")
                .queryParam("tot", "2026-11-25T10:00:00Z")
                .when().get(PAD)
                .then()
                .statusCode(400);
    }

    @Test
    void inzage_alsHijUitStaat_geeft404() {
        LogboekController uit = new LogboekController(false,
                new LogboekLezer(Optional.of(url), Optional.empty(), Optional.empty(), "logboek_spans"),
                hashHelper, new LogboekContext());

        HttpProblem fout = assertThrows(HttpProblem.class, () -> uit.logboekVerwerkingenOpvragen("BSN:999990020", null, null));

        assertEquals(404, fout.getStatusCode());
    }

    @Test
    void lezer_metOngeldigeTabelnaam_weigertTeStarten() {
        assertThrows(IllegalStateException.class,
                () -> new LogboekLezer(Optional.of(url), Optional.empty(), Optional.empty(), "spans; DROP TABLE x"));
    }

    @Test
    void lezer_zonderUrl_meldtDatHetLogboekNietLeesbaarIs() {
        LogboekLezer zonderUrl = new LogboekLezer(Optional.empty(), Optional.empty(), Optional.empty(), "logboek_spans");

        assertThrows(LogboekLezer.LogboekNietLeesbaarException.class,
                () -> zonderUrl.lees("BSN", "hash", null, null));
    }

    // Zoals de wrapper schrijft: de betrokkene als sleutel-hash, het type ernaast.
    private void voegToe(String trace, String span, String parent, String status, String naam, Instant start,
                         String betrokkene, String type, String foutType) throws SQLException {
        String attributen = """
                {"dpl.core.data_subject_id": "%s", "dpl.core.data_subject_id_type": "%s",
                 "dpl.core.processing_activity_id": "https://mijnoverheidzakelijk.nl/verwerkingsactiviteiten/TODO-NMC"%s}"""
                .formatted(hashHelper.hashIdentifier(betrokkene), type,
                        foutType == null ? "" : ", \"exception.type\": \"" + foutType + "\"");
        try (Connection c = DriverManager.getConnection(url, "postgres", "postgres");
             PreparedStatement p = c.prepareStatement("""
                     INSERT INTO logboek_spans (trace_id, span_id, parent_span_id, status, name, start_time, end_time, attributes)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)""")) {
            p.setString(1, trace);
            p.setString(2, span);
            p.setString(3, parent);
            p.setString(4, status);
            p.setString(5, naam);
            p.setLong(6, start.toEpochMilli());
            p.setLong(7, start.toEpochMilli() + 5);
            p.setString(8, attributen);
            p.executeUpdate();
        }
    }
}
