package nl.rijksoverheid.moz.nmc.controller;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLJwtFactory;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailRequest;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailResponse;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;

@QuarkusTest
class DecentraleNotificatieControllerTest {

    @InjectMock
    @RestClient
    SendAMessageApi sendAMessageApi;

    @InjectMock
    NotifyNLJwtFactory notifyNLJwtFactory;

    @BeforeEach
    void setUp() {
        Mockito.when(notifyNLJwtFactory.authorizationHeader(any())).thenReturn("Bearer test-token");
    }

    @Test
    void decentraleNotificatieVersturen_happyFlow_verstuurtNaarOpgegevenEmailEnTemplate() {
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(notifyResponse(UUID.randomUUID()));

        given()
                .contentType(ContentType.JSON)
                .body(standaardAanvraag())
                .when().post("/api/nmc/v1/decentraal/notificaties")
                .then()
                .statusCode(200)
                .body("notificatieId", org.hamcrest.Matchers.notNullValue());

        // Kern van het decentraal profiel: NotifyNL krijgt exact het meegegeven e-mailadres en de template
        // die bij het berichttype hoort — niet een opgezocht adres.
        ArgumentCaptor<SendEmailRequest> verzoek = ArgumentCaptor.forClass(SendEmailRequest.class);
        Mockito.verify(sendAMessageApi).sendEmail(verzoek.capture());
        assertEquals("burger@example.nl", verzoek.getValue().getEmailAddress());
        assertEquals("e72c75c5-e74c-4a78-8f2d-c06187a4d51c", verzoek.getValue().getTemplateId());
    }

    @Test
    void decentraleNotificatieVersturen_metBerichtgegevens_retourneert200() {
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(notifyResponse(UUID.randomUUID()));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "emailAdres": "burger@example.nl",
                          "berichtType": "Stuurgroep Agenda",
                          "berichtgegevens": { "naam": "Voorbeeld BV" }
                        }
                        """)
                .when().post("/api/nmc/v1/decentraal/notificaties")
                .then()
                .statusCode(200);
    }

    @Test
    void decentraleNotificatieVersturen_metCallbackUrl_retourneert200() {
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(notifyResponse(UUID.randomUUID()));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "emailAdres": "burger@example.nl",
                          "berichtType": "Stuurgroep Agenda",
                          "callbackUrl": "https://aanroeper.example.nl/status"
                        }
                        """)
                .when().post("/api/nmc/v1/decentraal/notificaties")
                .then()
                .statusCode(200);
    }

    @Test
    void decentraleNotificatieVersturen_ontbrekendEmailAdres_retourneert400() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "berichtType": "Stuurgroep Agenda"
                        }
                        """)
                .when().post("/api/nmc/v1/decentraal/notificaties")
                .then()
                .statusCode(400);
    }

    @Test
    void decentraleNotificatieVersturen_ongeldigEmailAdres_retourneert400() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "emailAdres": "geen-geldig-emailadres",
                          "berichtType": "Stuurgroep Agenda"
                        }
                        """)
                .when().post("/api/nmc/v1/decentraal/notificaties")
                .then()
                .statusCode(400);
    }

    @Test
    void decentraleNotificatieVersturen_onbekendBerichtType_retourneert400() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "emailAdres": "burger@example.nl",
                          "berichtType": "Onbekend Type XYZ"
                        }
                        """)
                .when().post("/api/nmc/v1/decentraal/notificaties")
                .then()
                .statusCode(400)
                .contentType("application/problem+json");
    }

    @Test
    void decentraleNotificatieVersturen_notifyGeenNotificatieId_retourneert500() {
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(new SendEmailResponse());

        given()
                .contentType(ContentType.JSON)
                .body(standaardAanvraag())
                .when().post("/api/nmc/v1/decentraal/notificaties")
                .then()
                .statusCode(500)
                .contentType("application/problem+json");
    }

    @Test
    void decentraleNotificatieVersturen_notifyGeeftFoutstatus_retourneert500() {
        Mockito.when(sendAMessageApi.sendEmail(any()))
                .thenThrow(new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).build()));

        given()
                .contentType(ContentType.JSON)
                .body(standaardAanvraag())
                .when().post("/api/nmc/v1/decentraal/notificaties")
                .then()
                .statusCode(500)
                .contentType("application/problem+json");
    }

    @Test
    void decentraleNotificatieVersturen_ongeldigeApiKeyConfiguratie_retourneert500() {
        Mockito.when(notifyNLJwtFactory.authorizationHeader(any()))
                .thenThrow(new IllegalArgumentException("Ongeldige key"));

        given()
                .contentType(ContentType.JSON)
                .body(standaardAanvraag())
                .when().post("/api/nmc/v1/decentraal/notificaties")
                .then()
                .statusCode(500)
                .contentType("application/problem+json");
    }

    private String standaardAanvraag() {
        return """
                {
                  "emailAdres": "burger@example.nl",
                  "berichtType": "Stuurgroep Agenda"
                }
                """;
    }

    private SendEmailResponse notifyResponse(UUID notifyReferentie) {
        return new SendEmailResponse().id(notifyReferentie.toString());
    }
}
