package nl.rijksoverheid.moz.nmc.controller;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
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
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

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
    void notificatieVersturen_happyFlow_retourneert202MetLocationEnPlantDeVerzendtaak() {
        String id = given()
                .contentType(ContentType.JSON)
                .body(aanvraag("Stuurgroep Agenda", "\"dienst\": \"Parkeervergunning\","))
                .when().post(PAD)
                .then()
                .statusCode(202)
                .header("Location", equalTo("/api/nmc/v1/notificaties/" + idUitDatabase()))
                .body("notificatieId", notNullValue())
                .extract().path("notificatieId");

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(NotificatieStatus.AANGENOMEN, notificatieRepository.findById(UUID.fromString(id)).getStatus());
            assertEquals(TaakSoort.VERZENDEN, taakRepository.listAll().getFirst().getSoort());
        });
        verifyNoInteractions(profielApi, sendAMessageApi);
    }

    @Test
    void notificatieVersturen_zonderDienst_retourneert202() {
        given()
                .contentType(ContentType.JSON)
                .body(aanvraag("Stuurgroep Agenda", ""))
                .when().post(PAD)
                .then()
                .statusCode(202);
    }

    // callbackUrl staat niet meer in het contract; een aanroeper die hem nog meestuurt, krijgt geen
    // fout, en de URL wordt nergens gebruikt.
    @Test
    void notificatieVersturen_metVervallenCallbackUrl_retourneert202() {
        given()
                .contentType(ContentType.JSON)
                .body(aanvraag("Stuurgroep Agenda", "\"callbackUrl\": \"http://127.0.0.1/cb\","))
                .when().post(PAD)
                .then()
                .statusCode(202);
    }

    @Test
    void notificatieVersturen_ontbrekendIdentificatieNummer_retourneert400() {
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
    void notificatieVersturen_leegIdentificatieNummer_retourneert400() {
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

    @Test
    void notificatieVersturen_onbekendBerichtType_retourneert400() {
        given()
                .contentType(ContentType.JSON)
                .body(aanvraag("Bestaat Niet", ""))
                .when().post(PAD)
                .then()
                .statusCode(400)
                .body("title", equalTo("Notificatie niet aangenomen."));
    }

    @Test
    void notificatieVersturen_quotumBereikt_retourneert429MetEigenType() {
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

    private String idUitDatabase() {
        return QuarkusTransaction.requiringNew().call(() -> notificatieRepository.listAll().getFirst().getId().toString());
    }

    private void zetQuotum(Integer quotum) {
        QuarkusTransaction.requiringNew().run(() -> entityManager
                .createNativeQuery("UPDATE dienstverlener SET quotum_per_dag = ?1 WHERE id = ?2")
                .setParameter(1, quotum).setParameter(2, NotificatieFixtures.DV_ID).executeUpdate());
    }
}
