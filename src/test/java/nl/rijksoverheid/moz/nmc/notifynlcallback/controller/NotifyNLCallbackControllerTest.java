package nl.rijksoverheid.moz.nmc.notifynlcallback.controller;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.ConsumentCallbackAdapter;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.StatusUpdateOpdracht;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLJwtFactory;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.api.ProfielApi;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.ContactgegevenResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.PartijResponse;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;

@QuarkusTest
class NotifyNLCallbackControllerTest {

    // Een vast tijdstip in het verleden: moet herkenbaar verschillen van het moment waarop de test
    // de callback verwerkt, anders bewijst de assertie op het receipttijdstip niets.
    private static final String COMPLETED_AT = "2025-01-01T12:00:02Z";

    // Moet overeenkomen met %test.notify.callback.bearer-token in application.properties
    private static final String CALLBACK_BEARER_TOKEN = "test-callback-token-niet-voor-productie";

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

    @Inject
    PogingRepository pogingRepository;

    @Inject
    EventRepository eventRepository;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            eventRepository.deleteAll();
            notificatieRepository.deleteAll();
        });

        Mockito.when(notifyNLJwtFactory.authorizationHeader(any())).thenReturn("Bearer test-token");
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any())).thenReturn(partijMetEmail("test@example.nl"));
    }

    @Test
    void verwerkAfleverstatus_gelukt_retourneert204EnZetDeNotificatieOpBezorgd() {
        UUID notifyNlId = verstuurNotificatie(null);

        stuurDeliveryReceipt(deliveryReceipt(notifyNlId, "delivered"));

        assertEquals(NotificatieStatus.BEZORGD, status(notifyNlId));
    }

    @Test
    void verwerkAfleverstatus_onbekendId_retourneert404() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + CALLBACK_BEARER_TOKEN)
                .body(deliveryReceipt(UUID.randomUUID(), "delivered"))
                .when().post("/api/nmc/v1/notifynl-callback")
                .then()
                .statusCode(404)
                .contentType("application/problem+json");
    }

    @Test
    void verwerkAfleverstatus_ongeldigBearerToken_retourneert401() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer ongeldig-token")
                .body(deliveryReceipt(UUID.randomUUID(), "delivered"))
                .when().post("/api/nmc/v1/notifynl-callback")
                .then()
                .statusCode(401)
                .contentType("application/problem+json");
    }

    @Test
    void verwerkAfleverstatus_geenBearerToken_retourneert401() {
        given()
                .contentType(ContentType.JSON)
                .body(deliveryReceipt(UUID.randomUUID(), "delivered"))
                .when().post("/api/nmc/v1/notifynl-callback")
                .then()
                .statusCode(401)
                .contentType("application/problem+json");
    }

    // Een status die de NMC niet kent krijgt 204, zodat NotifyNL niet blijft herhalen, maar verandert
    // niets en gaat niet naar de Dienstverlener.
    @Test
    void verwerkAfleverstatus_onbekendeStatus_retourneert204ZonderStatusupdate() {
        UUID notifyNlId = verstuurNotificatie("https://omc.example.com/callback");

        stuurDeliveryReceipt(deliveryReceipt(notifyNlId, "some-unknown-status"));

        assertEquals(NotificatieStatus.VERZONDEN, status(notifyNlId));
        Mockito.verify(consumentCallbackAdapter, never()).stuurStatusUpdate(any());
    }

    // completed_at is het tijdstip van déze status en bepaalt de volgorde van receipts; het moment
    // van verwerken hoort er niet in.
    @Test
    void verwerkAfleverstatus_legtCompletedAtVastOpDePoging() {
        UUID notifyNlId = verstuurNotificatie(null);

        stuurDeliveryReceipt(deliveryReceipt(notifyNlId, "delivered"));

        Poging poging = poging(notifyNlId);
        assertEquals(PogingStatus.BEZORGD, poging.getStatus());
        assertEquals(OffsetDateTime.parse(COMPLETED_AT), poging.getReceiptTijdstip());
    }

    // Geen van de tijdstipvelden is verplicht; een 400 zou de receipt na vijf herhalingen kosten.
    @Test
    void verwerkAfleverstatus_receiptZonderTijdstippen_wordtVerwerktOpDeEigenKlok() {
        UUID notifyNlId = verstuurNotificatie(null);
        OffsetDateTime voorCallback = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

        stuurDeliveryReceipt("""
                {"id": "%s", "status": "delivered"}
                """.formatted(notifyNlId));

        assertEquals(NotificatieStatus.BEZORGD, status(notifyNlId));
        assertFalse(poging(notifyNlId).getReceiptTijdstip().isBefore(voorCallback));
    }

    @Test
    void verwerkAfleverstatus_metCallbackUrl_stuurtDeOvergangNaarDeDienstverlener() {
        UUID notifyNlId = verstuurNotificatie("https://omc.example.com/callback");

        stuurDeliveryReceipt(deliveryReceipt(notifyNlId, "delivered"));

        ArgumentCaptor<StatusUpdateOpdracht> captor = ArgumentCaptor.forClass(StatusUpdateOpdracht.class);
        Mockito.verify(consumentCallbackAdapter).stuurStatusUpdate(captor.capture());
        assertEquals("https://omc.example.com/callback", captor.getValue().callbackUrl());
        assertEquals(NotificatieStatus.BEZORGD, captor.getValue().naar());
        assertEquals(3, captor.getValue().versie());
    }

    // De statusupdate naar de Dienstverlener staat los van het vastleggen: mislukt die, dan krijgt
    // NotifyNL alsnog 204 en blijft de overgang staan.
    @Test
    void verwerkAfleverstatus_mislukteConsumentCallback_geeftTochTweehonderdvierEnBewaartDeOvergang() {
        UUID notifyNlId = verstuurNotificatie("https://omc.example.com/callback");
        Mockito.doThrow(new IllegalStateException("consument onbereikbaar"))
                .when(consumentCallbackAdapter).stuurStatusUpdate(any());

        stuurDeliveryReceipt(deliveryReceipt(notifyNlId, "delivered"));

        assertEquals(NotificatieStatus.BEZORGD, status(notifyNlId));
        Mockito.reset(consumentCallbackAdapter);
    }

    private UUID verstuurNotificatie(String callbackUrl) {
        UUID notifyNlId = UUID.randomUUID();
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(new SendEmailResponse().id(notifyNlId.toString()));

        given()
                .contentType(ContentType.JSON)
                .body(aanvraag(callbackUrl))
                .when().post("/api/nmc/v1/centraal/notificaties")
                .then().statusCode(200);

        return notifyNlId;
    }

    private static void stuurDeliveryReceipt(String body) {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + CALLBACK_BEARER_TOKEN)
                .body(body)
                .when().post("/api/nmc/v1/notifynl-callback")
                .then()
                .statusCode(204);
    }

    private Poging poging(UUID notifyNlId) {
        return QuarkusTransaction.requiringNew().call(() -> pogingRepository.findByNotifyId(notifyNlId).orElseThrow());
    }

    private NotificatieStatus status(UUID notifyNlId) {
        return QuarkusTransaction.requiringNew().call(() ->
                notificatieRepository.findById(pogingRepository.findByNotifyId(notifyNlId).orElseThrow().getNotificatieId())
                        .getStatus());
    }

    private static String aanvraag(String callbackUrl) {
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

    private static String deliveryReceipt(UUID notifyNlId, String status) {
        return """
                {
                  "id": "%s",
                  "to": "test@example.nl",
                  "status": "%s",
                  "notification_type": "email",
                  "created_at": "2025-01-01T12:00:00Z",
                  "sent_at": "2025-01-01T12:00:01Z",
                  "completed_at": "%s"
                }
                """.formatted(notifyNlId, status, COMPLETED_AT);
    }

    private static PartijResponse partijMetEmail(String email) {
        ContactgegevenResponse contactgegeven = new ContactgegevenResponse()
                .type(ContactgegevenResponse.TypeEnum.EMAIL)
                .waarde(email)
                .isDefault(true);

        return new PartijResponse()
                .partijId(UUID.randomUUID())
                .contactgegevens(List.of(contactgegeven));
    }
}
