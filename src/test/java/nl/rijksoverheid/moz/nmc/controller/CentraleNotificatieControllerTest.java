package nl.rijksoverheid.moz.nmc.controller;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLJwtFactory;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.api.ProfielApi;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.ApiProfielserviceV1VoorkeurPost201ResponseScopesInner;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.ContactgegevenResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.PartijResponse;
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
class CentraleNotificatieControllerTest {

    @InjectMock
    @RestClient
    ProfielApi profielApi;

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
    void notificatieVersturen_happyFlow_retourneert200() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any())).thenReturn(partijMetEmail("burger@example.nl", true));

        UUID notifyNlId = UUID.randomUUID();
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(notifyResponse(notifyNlId));

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
    void notificatieVersturen_geenEmailadresGevonden_retourneert400() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any())).thenReturn(partijZonderEmail());

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
                .statusCode(400)
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
    void notificatieVersturen_notifyGeenNotificatieId_retourneert500() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any())).thenReturn(partijMetEmail("burger@example.nl", true));

        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(new SendEmailResponse());

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
                .statusCode(500)
                .contentType("application/problem+json");
    }

    @Test
    void notificatieVersturen_notifyGeenStatus201_retourneert500() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any())).thenReturn(partijMetEmail("burger@example.nl", true));

        Mockito.when(sendAMessageApi.sendEmail(any()))
                .thenThrow(new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).build()));

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
                .statusCode(500)
                .contentType("application/problem+json");
    }

    @Test
    void notificatieVersturen_partijNietGevonden_retourneert400() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any()))
                .thenThrow(new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build()));

        given()
                .contentType(ContentType.JSON)
                .body(standaardAanvraag())
                .when().post("/api/nmc/v1/notificaties")
                .then()
                .statusCode(400)
                .contentType("application/problem+json");
    }

    @Test
    void notificatieVersturen_profielserviceFout_retourneert500() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any()))
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
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any())).thenReturn(partijMetEmail("burger@example.nl", true));
        Mockito.when(notifyNLJwtFactory.authorizationHeader(any()))
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
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any()))
                .thenReturn(partijMetEnkelGescopedeEmail("scoped@example.nl", "Gemeente Voorbeeld", "Parkeervergunning"));
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(notifyResponse(UUID.randomUUID()));

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
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any()))
                .thenReturn(partijMetEnkelGescopedeEmail("dv-scoped@example.nl", "Gemeente Voorbeeld", null));
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(notifyResponse(UUID.randomUUID()));

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
        PartijResponse partij = new PartijResponse()
                .partijId(UUID.randomUUID())
                .contactgegevens(List.of(
                        gescopedeEmail("wrong-scoped@example.nl", "Andere Dienstverlener", "Parkeervergunning"),
                        ongescopedeEmail("default@example.nl", true)));
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any())).thenReturn(partij);
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(notifyResponse(UUID.randomUUID()));

        given()
                .contentType(ContentType.JSON)
                .body(standaardAanvraag())
                .when().post("/api/nmc/v1/notificaties")
                .then()
                .statusCode(200);
    }

    @Test
    void notificatieVersturen_alleEmailsMetVerkeerdeScope_retourneert400() {
        // All emails scoped to wrong dienstverlener, no fallback available
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any()))
                .thenReturn(partijMetEnkelGescopedeEmail("wrong@example.nl", "Andere Dienstverlener", "Parkeervergunning"));

        given()
                .contentType(ContentType.JSON)
                .body(standaardAanvraag())
                .when().post("/api/nmc/v1/notificaties")
                .then()
                .statusCode(400)
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
        return new PartijResponse()
                .partijId(UUID.randomUUID())
                .contactgegevens(List.of(gescopedeEmail(email, dienstverlenerNaam, dienstNaam)));
    }

    private ContactgegevenResponse gescopedeEmail(String email, String dienstverlenerNaam, String dienstNaam) {
        ApiProfielserviceV1VoorkeurPost201ResponseScopesInner scope = new ApiProfielserviceV1VoorkeurPost201ResponseScopesInner()
                .dienstverlenerNaam(dienstverlenerNaam)
                .dienstNaam(dienstNaam);

        return new ContactgegevenResponse()
                .type(ContactgegevenResponse.TypeEnum.EMAIL)
                .waarde(email)
                .scopes(List.of(scope));
    }

    private ContactgegevenResponse ongescopedeEmail(String email, boolean isDefault) {
        return new ContactgegevenResponse()
                .type(ContactgegevenResponse.TypeEnum.EMAIL)
                .waarde(email)
                .isDefault(isDefault);
    }

    private PartijResponse partijMetEmail(String email, boolean isDefault) {
        return new PartijResponse()
                .partijId(UUID.randomUUID())
                .contactgegevens(List.of(ongescopedeEmail(email, isDefault)));
    }

    private PartijResponse partijZonderEmail() {
        return new PartijResponse()
                .partijId(UUID.randomUUID())
                .contactgegevens(List.of());
    }

    private SendEmailResponse notifyResponse(UUID notifyReferentie) {
        return new SendEmailResponse().id(notifyReferentie.toString());
    }
}
