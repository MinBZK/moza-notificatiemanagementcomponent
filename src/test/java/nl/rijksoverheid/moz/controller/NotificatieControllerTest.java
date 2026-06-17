package nl.rijksoverheid.moz.controller;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.client.notify.NotifyClient;
import nl.rijksoverheid.moz.client.notify.NotifyEmailResponse;
import nl.rijksoverheid.moz.client.notify.NotifyJwtFactory;
import nl.rijksoverheid.moz.client.profielservice.ContactgegevenResponse;
import nl.rijksoverheid.moz.client.profielservice.PartijResponse;
import nl.rijksoverheid.moz.client.profielservice.ProfielServiceClient;
import nl.rijksoverheid.moz.client.profielservice.ScopeResponse;
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
        Response notifyResponse = notifyResponse(201, notifyNlId);
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
        Mockito.when(notifyResponse.getStatus()).thenReturn(201);
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
    void notificatieVersturen_notifyGeenStatus201_retourneert502() {
        Mockito.when(profielServiceClient.zoekPartij(any())).thenReturn(partijMetEmail("burger@example.nl", true));

        Response notifyResponse = Mockito.mock(Response.class);
        Mockito.when(notifyResponse.getStatus()).thenReturn(400);
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
    void notificatieVersturen_partijNietGevonden_retourneert404() {
        Mockito.when(profielServiceClient.zoekPartij(any()))
                .thenThrow(new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build()));

        given()
                .contentType(ContentType.JSON)
                .body(standaardAanvraag())
                .when().post("/api/nmc/v1/notificaties")
                .then()
                .statusCode(404)
                .contentType("application/problem+json");
    }

    @Test
    void notificatieVersturen_profielserviceFout_retourneert500() {
        Mockito.when(profielServiceClient.zoekPartij(any()))
                .thenThrow(new WebApplicationException(Response.status(Response.Status.INTERNAL_SERVER_ERROR).build()));

        given()
                .contentType(ContentType.JSON)
                .body(standaardAanvraag())
                .when().post("/api/nmc/v1/notificaties")
                .then()
                .statusCode(500)
                .contentType("application/problem+json");
    }

    @Test
    void notificatieVersturen_ongeldigeApiKeyConfiguratie_retourneert500() {
        Mockito.when(profielServiceClient.zoekPartij(any())).thenReturn(partijMetEmail("burger@example.nl", true));
        Mockito.when(notifyJwtFactory.authorizationHeader(any()))
                .thenThrow(new IllegalArgumentException("Ongeldige key"));

        given()
                .contentType(ContentType.JSON)
                .body(standaardAanvraag())
                .when().post("/api/nmc/v1/notificaties")
                .then()
                .statusCode(500)
                .contentType("application/problem+json");
    }

    @Test
    void notificatieVersturen_emailMetExacteScope_wordtGekozen() {
        // Only a scoped email exists (no default/unscoped), so a 200 proves the scope lookup works
        Mockito.when(profielServiceClient.zoekPartij(any()))
                .thenReturn(partijMetEnkelGescopedeEmail("scoped@example.nl", "Gemeente Voorbeeld", "Parkeervergunning"));
        Response notifyResp = notifyResponse(201, UUID.randomUUID());
        Mockito.when(notifyClient.verstuurEmail(any(), any())).thenReturn(notifyResp);

        given()
                .contentType(ContentType.JSON)
                .body(standaardAanvraag())
                .when().post("/api/nmc/v1/notificaties")
                .then()
                .statusCode(200);
    }

    @Test
    void notificatieVersturen_emailMetDienstverlenerScopeZonderDienst_wordtGekozen() {
        // Scoped to dienstverlener only (no dienst), no default — should still be picked
        Mockito.when(profielServiceClient.zoekPartij(any()))
                .thenReturn(partijMetEnkelGescopedeEmail("dv-scoped@example.nl", "Gemeente Voorbeeld", null));
        Response notifyResp = notifyResponse(201, UUID.randomUUID());
        Mockito.when(notifyClient.verstuurEmail(any(), any())).thenReturn(notifyResp);

        given()
                .contentType(ContentType.JSON)
                .body(standaardAanvraag())
                .when().post("/api/nmc/v1/notificaties")
                .then()
                .statusCode(200);
    }

    @Test
    void notificatieVersturen_emailMetVerkeerdeScope_valtTerugOpDefault() {
        // Wrong-scoped email + unscoped default — default should be picked, not the scoped one
        PartijResponse partij = new PartijResponse();
        partij.partijId = UUID.randomUUID();
        partij.contactgegevens = List.of(
                gescopedeEmail("wrong-scoped@example.nl", "Andere Dienstverlener", "Parkeervergunning"),
                ongescopedeEmail("default@example.nl", true));
        Mockito.when(profielServiceClient.zoekPartij(any())).thenReturn(partij);
        Response notifyResp = notifyResponse(201, UUID.randomUUID());
        Mockito.when(notifyClient.verstuurEmail(any(), any())).thenReturn(notifyResp);

        given()
                .contentType(ContentType.JSON)
                .body(standaardAanvraag())
                .when().post("/api/nmc/v1/notificaties")
                .then()
                .statusCode(200);
    }

    @Test
    void notificatieVersturen_alleEmailsMetVerkeerdeScope_retourneert404() {
        // All emails scoped to wrong dienstverlener, no fallback available
        Mockito.when(profielServiceClient.zoekPartij(any()))
                .thenReturn(partijMetEnkelGescopedeEmail("wrong@example.nl", "Andere Dienstverlener", "Parkeervergunning"));

        given()
                .contentType(ContentType.JSON)
                .body(standaardAanvraag())
                .when().post("/api/nmc/v1/notificaties")
                .then()
                .statusCode(404)
                .contentType("application/problem+json");
    }

    private String standaardAanvraag() {
        return """
                {
                  "identificatieType": "KVK",
                  "identificatieNummer": "12345678",
                  "dienstverlener": "Gemeente Voorbeeld",
                  "dienst": "Parkeervergunning"
                }
                """;
    }

    private PartijResponse partijMetEnkelGescopedeEmail(String email, String dienstverlenerNaam, String dienstNaam) {
        PartijResponse partij = new PartijResponse();
        partij.partijId = UUID.randomUUID();
        partij.contactgegevens = List.of(gescopedeEmail(email, dienstverlenerNaam, dienstNaam));
        return partij;
    }

    private ContactgegevenResponse gescopedeEmail(String email, String dienstverlenerNaam, String dienstNaam) {
        ScopeResponse scope = new ScopeResponse();
        scope.dienstverlenerNaam = dienstverlenerNaam;
        scope.dienstNaam = dienstNaam;

        ContactgegevenResponse contactgegeven = new ContactgegevenResponse();
        contactgegeven.type = ContactType.Email;
        contactgegeven.waarde = email;
        contactgegeven.scopes = List.of(scope);
        return contactgegeven;
    }

    private ContactgegevenResponse ongescopedeEmail(String email, boolean isDefault) {
        ContactgegevenResponse contactgegeven = new ContactgegevenResponse();
        contactgegeven.type = ContactType.Email;
        contactgegeven.waarde = email;
        contactgegeven.isDefault = isDefault;
        return contactgegeven;
    }

    private PartijResponse partijMetEmail(String email, boolean isDefault) {
        PartijResponse partij = new PartijResponse();
        partij.partijId = UUID.randomUUID();
        partij.contactgegevens = List.of(ongescopedeEmail(email, isDefault));
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
