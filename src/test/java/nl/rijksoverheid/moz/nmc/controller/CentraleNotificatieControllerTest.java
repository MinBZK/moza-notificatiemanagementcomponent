package nl.rijksoverheid.moz.nmc.controller;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.api.ProfielApi;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TaakFixtures;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;

/** De intake is een aanname: 202 na de commit, zonder aanroep van de Profielservice of NotifyNL. */
@QuarkusTest
class CentraleNotificatieControllerTest {

    private static final String PAD = "/api/nmc/v1/centraal/notificaties";

    @InjectMock
    @RestClient
    ProfielApi profielApi;

    @InjectMock
    @RestClient
    SendAMessageApi sendAMessageApi;

    @Inject
    NotificatieRepository notificatieRepository;

    @Inject
    TaakRepository taakRepository;

    @Inject
    EntityManager entityManager;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            taakRepository.deleteAll();
            notificatieRepository.deleteAll();
        });
    }

    @AfterEach
    void quotumWeg() {
        zetQuotum(null);
    }

    @Test
    void notificatieAannemen_happyFlow_retourneert202EnPlantDeVerzendtaak() {
        String id = given()
                .contentType(ContentType.JSON)
                .body(aanvraag("Stuurgroep Agenda", "\"dienst\": \"Parkeervergunning\","))
                .when().post(PAD)
                .then()
                .statusCode(202)
                .body("notificatieId", notNullValue())
                .extract().path("notificatieId");

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(NotificatieStatus.AANGENOMEN, notificatieRepository.findById(UUID.fromString(id)).getStatus());
            assertEquals(TaakSoort.VERZENDEN, taakRepository.listAll().getFirst().getTaakSoort());
        });
        verifyNoInteractions(profielApi, sendAMessageApi);
    }

    @Test
    void notificatieAannemen_zonderDienst_retourneert202() {
        given()
                .contentType(ContentType.JSON)
                .body(aanvraag("Stuurgroep Agenda", ""))
                .when().post(PAD)
                .then()
                .statusCode(202);
    }

    @Test
    void notificatieAannemen_ontbrekendIdentificatieNummer_retourneert400() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "identificatieType": "KVK",
                          "dienstverlener": "Gemeente Voorbeeld",
                          "berichtType": "Stuurgroep Agenda"
                        }
                        """)
                .when().post(PAD)
                .then()
                .statusCode(400);
    }

    @Test
    void notificatieAannemen_leegIdentificatieNummer_retourneert400() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "identificatieType": "KVK",
                          "identificatieNummer": " ",
                          "dienstverlener": "Gemeente Voorbeeld",
                          "berichtType": "Stuurgroep Agenda"
                        }
                        """)
                .when().post(PAD)
                .then()
                .statusCode(400);
    }

    // Een KVK-nummer is 8 cijfers, een BSN 8 of 9 en een RSIN 9; al het andere is geen nummer.
    @ParameterizedTest
    @ValueSource(strings = {"1234567", "1234567890", "1234567a", "12.345.678", " 12345678", "12345678 ", "１２３４５６７８"})
    void notificatieAannemen_identificatieNummerGeen8Of9Cijfers_retourneert400(String nummer) {
        given()
                .contentType(ContentType.JSON)
                .body(aanvraagMetNummer(nummer))
                .when().post(PAD)
                .then()
                .statusCode(400);

        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678", "123456789", "012345678"})
    void notificatieAannemen_identificatieNummerVan8Of9Cijfers_retourneert202(String nummer) {
        given()
                .contentType(ContentType.JSON)
                .body(aanvraagMetNummer(nummer))
                .when().post(PAD)
                .then()
                .statusCode(202);
    }

    // De Profielservice wijst deze waarden met een 400 af; de intake weigert ze daarom zelf.
    @ParameterizedTest
    @ValueSource(strings = {
            "\"dienstverlener\": \" \"",
            "\"dienstverlener\": \"Gemeente\\nVoorbeeld\"",
            "\"dienstverlener\": \"Gemeente Voorbeeld\", \"dienst\": \" \"",
            "\"dienstverlener\": \"Gemeente Voorbeeld\", \"dienst\": \"Parkeer\\nvergunning\""
    })
    void notificatieAannemen_dienstverlenerOfDienstBlancoOfMetRegeleinde_retourneert400(String velden) {
        given()
                .contentType(ContentType.JSON)
                .body("{\"identificatieType\": \"KVK\", \"identificatieNummer\": \"12345678\", " + velden
                        + ", \"berichtType\": \"Stuurgroep Agenda\"}")
                .when().post(PAD)
                .then()
                .statusCode(400);

        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count()));
    }

    @Test
    void notificatieAannemen_onbekendBerichtType_retourneert400() {
        given()
                .contentType(ContentType.JSON)
                .body(aanvraag("Bestaat Niet", ""))
                .when().post(PAD)
                .then()
                .statusCode(400)
                .body("title", equalTo("Notificatie niet aangenomen."));
    }

    @Test
    void notificatieAannemen_quotumBereikt_retourneert429MetEigenType() {
        zetQuotum(1);
        given().contentType(ContentType.JSON).body(aanvraag("Stuurgroep Agenda", "")).when().post(PAD).then().statusCode(202);

        given()
                .contentType(ContentType.JSON)
                .body(aanvraag("Stuurgroep Agenda", ""))
                .when().post(PAD)
                .then()
                .statusCode(429)
                .contentType("application/problem+json")
                .body("type", equalTo("https://mijnoverheidzakelijk.nl/nmc/problemen/quotum-overschreden"));
    }

    @Test
    void notificatieAannemen_meerDan50Berichtgegevens_retourneert400() {
        StringBuilder gegevens = new StringBuilder("{");
        for (int i = 1; i <= 51; i++) {
            gegevens.append(i > 1 ? ", " : "").append("\"v").append(i).append("\": \"x\"");
        }
        gegevens.append("}");

        given()
                .contentType(ContentType.JSON)
                .body(aanvraagMetBerichtgegevens(gegevens.toString()))
                .when().post(PAD)
                .then()
                .statusCode(400)
                .body("violations", notNullValue());
    }

    @Test
    void notificatieAannemen_berichtgegevenLangerDan2000Tekens_retourneert400() {
        given()
                .contentType(ContentType.JSON)
                .body(aanvraagMetBerichtgegevens("{ \"naam\": \"" + "x".repeat(2001) + "\" }"))
                .when().post(PAD)
                .then()
                .statusCode(400)
                .body("violations", notNullValue());
    }

    @Test
    void notificatieAannemen_naamVanBerichtgegevenLangerDan100Tekens_retourneert400() {
        given()
                .contentType(ContentType.JSON)
                .body(aanvraagMetBerichtgegevens("{ \"" + "n".repeat(101) + "\": \"Voorbeeld BV\" }"))
                .when().post(PAD)
                .then()
                .statusCode(400)
                .body("violations", notNullValue());

        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count()));
    }

    // Boven de grens van quarkus.http.limits.max-body-size leest de server de body niet in. Met
    // Expect: 100-continue wacht de client op het antwoord voordat hij de body stuurt; zonder dat
    // verbreekt de server de verbinding terwijl de client nog aan het sturen is.
    @Test
    void notificatieAannemen_bodyGroterDan1MB_retourneert413() {
        given()
                .config(RestAssured.config().httpClient(
                        HttpClientConfig.httpClientConfig().setParam("http.protocol.expect-continue", true)))
                .contentType(ContentType.JSON)
                .body(aanvraagMetBerichtgegevens("{ \"naam\": \"" + "x".repeat(1024 * 1024) + "\" }"))
                .when().post(PAD)
                .then()
                .statusCode(413);

        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count()));
    }

    // De grootste geldige aanvraag blijft ruim onder de grens op de body.
    @Test
    void notificatieAannemen_grootsteGeldigeBerichtgegevens_retourneert202() {
        String berichtgegevens = IntStream.range(0, 50)
                .mapToObj(i -> "\"" + "n".repeat(98) + "%02d".formatted(i) + "\": \"" + "x".repeat(2000) + "\"")
                .collect(Collectors.joining(", ", "{ ", " }"));

        given()
                .contentType(ContentType.JSON)
                .body(aanvraagMetBerichtgegevens(berichtgegevens))
                .when().post(PAD)
                .then()
                .statusCode(202);
    }

    @Test
    void notificatieAannemen_naamEnWaardeVanBerichtgegevenOpDeGrens_retourneert202() {
        given()
                .contentType(ContentType.JSON)
                .body(aanvraagMetBerichtgegevens("{ \"" + "n".repeat(100) + "\": \"" + "x".repeat(2000) + "\" }"))
                .when().post(PAD)
                .then()
                .statusCode(202);
    }

    private static String aanvraagMetBerichtgegevens(String berichtgegevens) {
        return """
                {
                  "identificatieType": "KVK",
                  "identificatieNummer": "12345678",
                  "dienstverlener": "Gemeente Voorbeeld",
                  "berichtType": "Stuurgroep Agenda",
                  "berichtgegevens": %s
                }
                """.formatted(berichtgegevens);
    }

    private static String aanvraag(String berichtType, String extraVeld) {
        return """
                {
                  "identificatieType": "KVK",
                  "identificatieNummer": "12345678",
                  "dienstverlener": "Gemeente Voorbeeld",
                  %s
                  "berichtType": "%s",
                  "berichtgegevens": { "naam": "Voorbeeld BV" }
                }
                """.formatted(extraVeld, berichtType);
    }

    private static String aanvraagMetNummer(String identificatieNummer) {
        return """
                {
                  "identificatieType": "KVK",
                  "identificatieNummer": "%s",
                  "dienstverlener": "Gemeente Voorbeeld",
                  "berichtType": "Stuurgroep Agenda"
                }
                """.formatted(identificatieNummer);
    }

    private void zetQuotum(Integer quotum) {
        QuarkusTransaction.requiringNew().run(() ->
                TaakFixtures.zetQuotumPerDag(entityManager, NotificatieFixtures.DV_ID, quotum));
    }
}
