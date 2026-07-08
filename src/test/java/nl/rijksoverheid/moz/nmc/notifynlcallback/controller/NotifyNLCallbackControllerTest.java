package nl.rijksoverheid.moz.nmc.notifynlcallback.controller;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.ConsumentCallbackAdapter;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.api.ProfielApi;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.ContactgegevenResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.PartijResponse;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLJwtFactory;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

@QuarkusTest
class NotifyNLCallbackControllerTest {

    @InjectMock
    @RestClient
    ProfielApi profielApi;

    @InjectMock
    @RestClient
    SendAMessageApi sendAMessageApi;

    @InjectMock
    NotifyNLJwtFactory notifyNLJwtFactory;

    @InjectMock
    ConsumentCallbackAdapter consumentCallbackAdapter;

    @Inject
    NotificatieRepository notificatieRepository;

    @BeforeEach
    void setUp() {
        // Voorkomt dat notificaties van een vorige test de lookup-by-notifyNlNotificatieId
        // in een volgende test beïnvloeden.
        QuarkusTransaction.requiringNew().run(notificatieRepository::deleteAll);

        Mockito.when(notifyNLJwtFactory.authorizationHeader(any())).thenReturn("Bearer test-token");
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any())).thenReturn(partijMetEmail("test@example.nl", true));
    }

    @Test
    void verwerkAfleverstatus_gelukt_retourneert204() {
        UUID notifyNlId = UUID.randomUUID();
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(notifyResponse(notifyNlId));

        // Zorg dat er een notificatie bestaat voor dit notifyNlId
        given()
                .contentType(ContentType.JSON)
                .body(aanvraag(null))
                .when().post("/api/nmc/v1/centraal/notificaties")
                .then().statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .body(deliveryReceipt(notifyNlId, "delivered"))
                .when().post("/api/nmc/v1/notifynl-callback")
                .then()
                .statusCode(204);
    }

    @Test
    void verwerkAfleverstatus_onbekendId_retourneert404() {
        given()
                .contentType(ContentType.JSON)
                .body(deliveryReceipt(UUID.randomUUID(), "delivered"))
                .when().post("/api/nmc/v1/notifynl-callback")
                .then()
                .statusCode(404)
                .contentType("application/problem+json");
    }

    @Test
    void verwerkAfleverstatus_onbekendStatus_slaatTechnischeFoutOp() {
        // Onbekende statussen van NotifyNL moeten als TECHNICAL_FAILURE worden opgeslagen
        UUID notifyNlId = UUID.randomUUID();
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(notifyResponse(notifyNlId));

        given()
                .contentType(ContentType.JSON)
                .body(aanvraag(null))
                .when().post("/api/nmc/v1/centraal/notificaties")
                .then().statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .body(deliveryReceipt(notifyNlId, "some-unknown-status"))
                .when().post("/api/nmc/v1/notifynl-callback")
                .then()
                .statusCode(204);

        ArgumentCaptor<Notificatie> captor = ArgumentCaptor.forClass(Notificatie.class);
        Mockito.verify(consumentCallbackAdapter).stuurStatusUpdate(captor.capture());
        assertEquals(NotificatieStatus.TECHNICAL_FAILURE, captor.getValue().getStatus());
    }

    @Test
    void verwerkAfleverstatus_metCallbackUrl_roeptConsumentCallbackAdapterAan() {
        UUID notifyNlId = UUID.randomUUID();
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(notifyResponse(notifyNlId));

        given()
                .contentType(ContentType.JSON)
                .body(aanvraag("https://omc.example.com/callback"))
                .when().post("/api/nmc/v1/centraal/notificaties")
                .then().statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .body(deliveryReceipt(notifyNlId, "delivered"))
                .when().post("/api/nmc/v1/notifynl-callback")
                .then()
                .statusCode(204);

        Mockito.verify(consumentCallbackAdapter).stuurStatusUpdate(any());
    }

    @Test
    void verwerkAfleverstatus_callbackSuccesvol_verwijdertNotificatieUitDatabase() {
        UUID notifyNlId = UUID.randomUUID();
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(notifyResponse(notifyNlId));
        Mockito.when(consumentCallbackAdapter.stuurStatusUpdate(any())).thenReturn(true);

        given()
                .contentType(ContentType.JSON)
                .body(aanvraag("https://omc.example.com/callback"))
                .when().post("/api/nmc/v1/centraal/notificaties")
                .then().statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .body(deliveryReceipt(notifyNlId, "delivered"))
                .when().post("/api/nmc/v1/notifynl-callback")
                .then()
                .statusCode(204);

        assertTrue(notificatieRepository.findByExternalReference(notifyNlId).isEmpty());
    }

    @Test
    void verwerkAfleverstatus_callbackMislukt_bewaartNotificatieInDatabase() {
        UUID notifyNlId = UUID.randomUUID();
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(notifyResponse(notifyNlId));
        Mockito.when(consumentCallbackAdapter.stuurStatusUpdate(any())).thenReturn(false);

        given()
                .contentType(ContentType.JSON)
                .body(aanvraag("https://omc.example.com/callback"))
                .when().post("/api/nmc/v1/centraal/notificaties")
                .then().statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .body(deliveryReceipt(notifyNlId, "delivered"))
                .when().post("/api/nmc/v1/notifynl-callback")
                .then()
                .statusCode(204);

        assertTrue(notificatieRepository.findByExternalReference(notifyNlId).isPresent());
    }

    private String aanvraag(String callbackUrl) {
        String callbackPart = callbackUrl != null
                ? """
                , "callbackUrl": "%s"
                """.formatted(callbackUrl)
                : "";
        return """
                {
                  "identificatieType": "KVK",
                  "identificatieNummer": "12345678",
                  "dienstverlener": "Gemeente Voorbeeld",
                  "dienst": "Parkeervergunning",
                  "berichtType": "Stuurgroep Agenda"%s
                }
                """.formatted(callbackPart);
    }

    private String deliveryReceipt(UUID notifyNlId, String status) {
        return """
                {
                  "id": "%s",
                  "to": "test@example.nl",
                  "status": "%s",
                  "notification_type": "email",
                  "created_at": "2025-01-01T12:00:00Z"
                }
                """.formatted(notifyNlId, status);
    }

    private PartijResponse partijMetEmail(String email, boolean isDefault) {
        ContactgegevenResponse contactgegeven = new ContactgegevenResponse()
                .type(ContactgegevenResponse.TypeEnum.EMAIL)
                .waarde(email)
                .isDefault(isDefault);

        return new PartijResponse()
                .partijId(UUID.randomUUID())
                .contactgegevens(List.of(contactgegeven));
    }

    private SendEmailResponse notifyResponse(UUID notifyNlId) {
        return new SendEmailResponse().id(notifyNlId.toString());
    }
}
