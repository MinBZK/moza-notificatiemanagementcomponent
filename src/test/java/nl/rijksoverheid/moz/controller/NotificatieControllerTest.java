package nl.rijksoverheid.moz.controller;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.client.notify.NotifyClient;
import nl.rijksoverheid.moz.client.notify.NotifyEmailResponse;
import nl.rijksoverheid.moz.client.notify.NotifyJwtFactory;
import nl.rijksoverheid.moz.client.profielservice.ContactgegevenResponse;
import nl.rijksoverheid.moz.client.profielservice.PartijResponse;
import nl.rijksoverheid.moz.client.profielservice.ProfielServiceClient;
import nl.rijksoverheid.moz.common.ContactType;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;

@QuarkusTest
class NotificatieControllerTest {

    @InjectMock
    @RestClient
    ProfielServiceClient profielServiceClient;

    @InjectMock
    @RestClient
    NotifyClient notifyClient;

    @InjectMock
    NotifyJwtFactory notifyJwtFactory;

    @BeforeEach
    void setUp() {
        Mockito.when(notifyJwtFactory.authorizationHeader(any())).thenReturn("Bearer test-token");
    }

    @Test
    void notificatieVersturen_happyFlow_retourneert200() {
        Mockito.when(profielServiceClient.zoekPartij(any())).thenReturn(partijMetEmail("burger@example.nl", true));

        UUID notifyNlId = UUID.randomUUID();
        Response notifyResponse = notifyResponse(200, notifyNlId);
        Mockito.when(notifyClient.verstuurEmail(any(), any())).thenReturn(notifyResponse);

        String aanvraag = """
                {
                  "identificatieType": "KVK",
                  "identificatieNummer": "12345678",
                  "dienstverlener": "Gemeente Voorbeeld",
                  "dienst": "Parkeervergunning",
                  "berichtgegevens": { "naam": "Voorbeeld BV" }
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(aanvraag)
                .when().post("/api/nmc/v1/notificaties")
                .then()
                .statusCode(200)
                .body("notificatieId", org.hamcrest.Matchers.notNullValue());
    }

    @Test
    void notificatieVersturen_geenEmailadresGevonden_retourneert404() {
        Mockito.when(profielServiceClient.zoekPartij(any())).thenReturn(partijZonderEmail());

        String aanvraag = """
                {
                  "identificatieType": "KVK",
                  "identificatieNummer": "12345678",
                  "dienstverlener": "Gemeente Voorbeeld",
                  "dienst": "Parkeervergunning"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(aanvraag)
                .when().post("/api/nmc/v1/notificaties")
                .then()
                .statusCode(404)
                .contentType("application/problem+json");
    }

    @Test
    void notificatieVersturen_ontbrekendIdentificatieNummer_retourneert400() {
        String aanvraag = """
                {
                  "identificatieType": "KVK",
                  "dienstverlener": "Gemeente Voorbeeld",
                  "dienst": "Parkeervergunning"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(aanvraag)
                .when().post("/api/nmc/v1/notificaties")
                .then()
                .statusCode(400);
    }

    @Test
    void notificatieVersturen_notifyGeenNotificatieId_retourneert502() {
        Mockito.when(profielServiceClient.zoekPartij(any())).thenReturn(partijMetEmail("burger@example.nl", true));

        Response notifyResponse = Mockito.mock(Response.class);
        Mockito.when(notifyResponse.getStatus()).thenReturn(200);
        Mockito.when(notifyResponse.readEntity(NotifyEmailResponse.class)).thenReturn(new NotifyEmailResponse());

        Mockito.when(notifyClient.verstuurEmail(any(), any())).thenReturn(notifyResponse);

        String aanvraag = """
                {
                  "identificatieType": "KVK",
                  "identificatieNummer": "12345678",
                  "dienstverlener": "Gemeente Voorbeeld",
                  "dienst": "Parkeervergunning"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(aanvraag)
                .when().post("/api/nmc/v1/notificaties")
                .then()
                .statusCode(502)
                .contentType("application/problem+json");
    }

    @Test
    void notificatieVersturen_notifyGeenStatus200_retourneert502() {
        Mockito.when(profielServiceClient.zoekPartij(any())).thenReturn(partijMetEmail("burger@example.nl", true));

        Response notifyResponse = Mockito.mock(Response.class);
        Mockito.when(notifyResponse.getStatus()).thenReturn(201);
        Mockito.when(notifyClient.verstuurEmail(any(), any())).thenReturn(notifyResponse);

        String aanvraag = """
                {
                  "identificatieType": "KVK",
                  "identificatieNummer": "12345678",
                  "dienstverlener": "Gemeente Voorbeeld",
                  "dienst": "Parkeervergunning"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(aanvraag)
                .when().post("/api/nmc/v1/notificaties")
                .then()
                .statusCode(502)
                .contentType("application/problem+json");
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

    private PartijResponse partijZonderEmail() {
        PartijResponse partij = new PartijResponse();
        partij.partijId = UUID.randomUUID();
        partij.contactgegevens = List.of();

        return partij;
    }

    private Response notifyResponse(int status, UUID notifyReferentie) {
        NotifyEmailResponse entity = new NotifyEmailResponse();
        entity.id = notifyReferentie;

        Response response = Mockito.mock(Response.class);
        Mockito.when(response.getStatus()).thenReturn(status);
        Mockito.when(response.readEntity(NotifyEmailResponse.class)).thenReturn(entity);

        return response;
    }
}
