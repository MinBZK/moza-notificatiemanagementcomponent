package nl.rijksoverheid.moz.nmc.notifynlcallback.controller;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.micrometer.core.instrument.MeterRegistry;
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
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.service.ReceiptVerwerker;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.job.TaakWorker;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    TaakWorker taakWorker;

    @Inject
    TaakRepository taakRepository;

    @Inject
    MeterRegistry meterRegistry;

    @InjectSpy
    ReceiptVerwerker receiptVerwerker;

    @Inject
    NotificatieRepository notificatieRepository;

    @Inject
    PogingRepository pogingRepository;

    @Inject
    EventRepository eventRepository;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            taakRepository.deleteAll();
            eventRepository.deleteAll();
            notificatieRepository.deleteAll();
            notificatieRepository.getEntityManager().createNativeQuery("DELETE FROM verzendbudget").executeUpdate();
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

    // Een receipt die bij geen poging hoort krijgt 2xx, zodat NotifyNL hem niet vijf keer herhaalt, en
    // wordt geteld maar niet opgeslagen.
    @Test
    void verwerkAfleverstatus_onbekendeReceipt_retourneert204ZonderTaakEnTeltHem() {
        double voor = meterRegistry.get("nmc.receipts.afgewezen").counter().count();

        stuurDeliveryReceiptZonderVerwerking(deliveryReceipt(UUID.randomUUID(), "delivered"));

        assertEquals(voor + 1, meterRegistry.get("nmc.receipts.afgewezen").counter().count());
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> taakRepository.count("soort", TaakSoort.RECEIPT_VERWERKEN)));
    }

    // De receipt ligt vast voordat hij verwerkt wordt, zonder het e-mailadres uit het veld `to`.
    @Test
    void verwerkAfleverstatus_slaatDeReceiptOpZonderEmailadres() {
        UUID notifyNlId = verstuurNotificatie(null);

        stuurDeliveryReceiptZonderVerwerking(deliveryReceipt(notifyNlId, "delivered"));

        Taak taak = QuarkusTransaction.requiringNew().call(() -> taakRepository.find("soort", TaakSoort.RECEIPT_VERWERKEN).singleResult());
        assertEquals("delivered", taak.getPayload().get("status"));
        assertTrue(taak.getPayload().values().stream().noneMatch(waarde -> waarde.contains("@")),
                "het e-mailadres staat niet in de payload: " + taak.getPayload());
        assertEquals(NotificatieStatus.VERZONDEN, status(notifyNlId), "verwerking volgt pas in de taak");
    }

    // NotifyNL herhaalt een receipt bij elke niet-2xx; dezelfde receipt levert één taak op.
    @Test
    void verwerkAfleverstatus_herhaaldeReceipt_levertEenTaak() {
        UUID notifyNlId = verstuurNotificatie(null);
        String receipt = deliveryReceipt(notifyNlId, "delivered");

        stuurDeliveryReceiptZonderVerwerking(receipt);
        stuurDeliveryReceiptZonderVerwerking(receipt);

        assertEquals(1L, QuarkusTransaction.requiringNew().call(() -> taakRepository.count("soort", TaakSoort.RECEIPT_VERWERKEN)));
    }

    // Een fout in de verwerking kost de receipt niet: de taak blijft staan en slaagt bij een volgende ronde.
    @Test
    void verwerkAfleverstatus_foutInDeVerwerking_verliestDeReceiptNiet() {
        UUID notifyNlId = verstuurNotificatie(null);
        stuurDeliveryReceiptZonderVerwerking(deliveryReceipt(notifyNlId, "delivered"));
        Mockito.doThrow(new IllegalStateException("gesimuleerde storing")).doCallRealMethod()
                .when(receiptVerwerker).verwerk(any(), any(), any(), any());

        taakWorker.verwerk(TaakSoort.RECEIPT_VERWERKEN);
        assertEquals(NotificatieStatus.VERZONDEN, status(notifyNlId));
        Taak taak = QuarkusTransaction.requiringNew().call(() -> taakRepository.find("soort", TaakSoort.RECEIPT_VERWERKEN).singleResult());
        QuarkusTransaction.requiringNew().run(() -> taakRepository.getEntityManager()
                .createNativeQuery("UPDATE taak SET due = now() - interval '1 second' WHERE id = ?1")
                .setParameter(1, taak.getId()).executeUpdate());

        taakWorker.verwerk(TaakSoort.RECEIPT_VERWERKEN);

        assertEquals(NotificatieStatus.BEZORGD, status(notifyNlId));
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> taakRepository.count("soort", TaakSoort.RECEIPT_VERWERKEN)));
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
                .then().statusCode(202);
        // De verzending is asynchroon: de verzendtaak zet de poging op het NotifyNL-id uit de mock.
        taakWorker.verwerk(TaakSoort.VERZENDEN);

        return notifyNlId;
    }

    // De callback slaat op; de receipttaak verwerkt.
    private void stuurDeliveryReceipt(String body) {
        stuurDeliveryReceiptZonderVerwerking(body);
        taakWorker.verwerk(TaakSoort.RECEIPT_VERWERKEN);
    }

    private static void stuurDeliveryReceiptZonderVerwerking(String body) {
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
