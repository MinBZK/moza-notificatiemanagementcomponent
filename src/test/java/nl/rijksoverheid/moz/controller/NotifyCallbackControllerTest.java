package nl.rijksoverheid.moz.controller;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.client.consumentcallback.ConsumentCallbackAdapter;
import nl.rijksoverheid.moz.client.notify.NotifyClient;
import nl.rijksoverheid.moz.client.notify.NotifyEmailResponse;
import nl.rijksoverheid.moz.client.profielservice.ContactgegevenResponse;
import nl.rijksoverheid.moz.client.profielservice.PartijResponse;
import nl.rijksoverheid.moz.client.profielservice.ProfielServiceClient;
import nl.rijksoverheid.moz.client.notify.NotifyJwtFactory;
import nl.rijksoverheid.moz.common.ContactType;
import nl.rijksoverheid.moz.common.NotificatieStatus;
import nl.rijksoverheid.moz.domain.Notificatie;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;

@QuarkusTest
class NotifyCallbackControllerTest {

    @InjectMock
    @RestClient
    ProfielServiceClient profielServiceClient;

    @InjectMock
    @RestClient
    NotifyClient notifyClient;

    @InjectMock
    NotifyJwtFactory notifyJwtFactory;

    @InjectMock
    ConsumentCallbackAdapter consumentCallbackAdapter;

    @BeforeEach
    void setUp() {
        Mockito.when(notifyJwtFactory.authorizationHeader(any())).thenReturn("Bearer test-token");
        Mockito.when(profielServiceClient.zoekPartij(any())).thenReturn(partijMetEmail("test@example.nl", true));
    }

    @Test
    void verwerkAfleverstatus_gelukt_retourneert204() {
        UUID notifyNlId = UUID.randomUUID();
        Response notifyNlResponse = notifyResponse(201, notifyNlId);
        Mockito.when(notifyClient.verstuurEmail(any(), any())).thenReturn(notifyNlResponse);

        // Zorg dat er een notificatie bestaat voor dit notifyNlId
        given()
                .contentType(ContentType.JSON)
                .body(aanvraag(null))
                .when().post("/api/nmc/v1/notificaties")
                .then().statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .body(deliveryReceipt(notifyNlId, "delivered"))
                .when().post("/api/nmc/v1/notify-callback")
                .then()
                .statusCode(204);
    }

    @Test
    void verwerkAfleverstatus_onbekendId_retourneert404() {
        given()
                .contentType(ContentType.JSON)
                .body(deliveryReceipt(UUID.randomUUID(), "delivered"))
                .when().post("/api/nmc/v1/notify-callback")
                .then()
                .statusCode(404)
                .contentType("application/problem+json");
    }

    @Test
    void verwerkAfleverstatus_onbekendStatus_slaatTechnischeFoutOp() {
        // Onbekende statussen van NotifyNL moeten als TECHNICAL_FAILURE worden opgeslagen
        UUID notifyNlId = UUID.randomUUID();
        Response notifyNlResponse = notifyResponse(201, notifyNlId);
        Mockito.when(notifyClient.verstuurEmail(any(), any())).thenReturn(notifyNlResponse);

        given()
                .contentType(ContentType.JSON)
                .body(aanvraag(null))
                .when().post("/api/nmc/v1/notificaties")
                .then().statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .body(deliveryReceipt(notifyNlId, "some-unknown-status"))
                .when().post("/api/nmc/v1/notify-callback")
                .then()
                .statusCode(204);

        ArgumentCaptor<Notificatie> captor = ArgumentCaptor.forClass(Notificatie.class);
        Mockito.verify(consumentCallbackAdapter).stuurStatusUpdate(captor.capture());
        assertEquals(NotificatieStatus.TECHNICAL_FAILURE, captor.getValue().status);
    }

    @Test
    void verwerkAfleverstatus_metCallbackUrl_roeptConsumentCallbackAdapterAan() {
        UUID notifyNlId = UUID.randomUUID();
        Response notifyNlResponse = notifyResponse(201, notifyNlId);
        Mockito.when(notifyClient.verstuurEmail(any(), any())).thenReturn(notifyNlResponse);

        given()
                .contentType(ContentType.JSON)
                .body(aanvraag("https://omc.example.com/callback"))
                .when().post("/api/nmc/v1/notificaties")
                .then().statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .body(deliveryReceipt(notifyNlId, "delivered"))
                .when().post("/api/nmc/v1/notify-callback")
                .then()
                .statusCode(204);

        Mockito.verify(consumentCallbackAdapter).stuurStatusUpdate(any());
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
                  "dienst": "Parkeervergunning"%s
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
        ContactgegevenResponse contactgegeven = new ContactgegevenResponse();
        contactgegeven.type = ContactType.Email;
        contactgegeven.waarde = email;
        contactgegeven.isDefault = isDefault;

        PartijResponse partij = new PartijResponse();
        partij.partijId = UUID.randomUUID();
        partij.contactgegevens = List.of(contactgegeven);

        return partij;
    }

    private Response notifyResponse(int status, UUID notifyNlId) {
        NotifyEmailResponse entity = new NotifyEmailResponse();
        entity.id = notifyNlId;

        Response response = Mockito.mock(Response.class);
        Mockito.when(response.getStatus()).thenReturn(status);
        Mockito.when(response.readEntity(NotifyEmailResponse.class)).thenReturn(entity);

        return response;
    }
}
