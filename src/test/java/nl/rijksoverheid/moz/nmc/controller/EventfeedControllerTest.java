package nl.rijksoverheid.moz.nmc.controller;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.ConsumentCallbackAdapter;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.StatusUpdateOpdracht;
import nl.rijksoverheid.moz.nmc.domain.Cursor;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.service.Aanroeplimiet;
import nl.rijksoverheid.moz.nmc.service.Overgangsfunctie;
import nl.rijksoverheid.moz.nmc.service.ReceiptVerwerker;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class EventfeedControllerTest {

    private static final String FEED = "/api/nmc/v1/notificaties/wijzigingen";
    private static final String BEVESTIGING = FEED + "/bevestiging";

    @InjectMock
    Aanroeplimiet aanroeplimiet;

    @InjectMock
    ConsumentCallbackAdapter consumentCallbackAdapter;

    @Inject
    ReceiptVerwerker receiptVerwerker;

    @Inject
    Overgangsfunctie overgangsfunctie;

    @Inject
    NotificatieRepository notificatieRepository;

    @Inject
    PogingRepository pogingRepository;

    @Inject
    EventRepository eventRepository;

    @Inject
    EntityManager entityManager;

    @BeforeEach
    void setUp() {
        when(aanroeplimiet.registreer(any())).thenReturn(true);
        QuarkusTransaction.requiringNew().run(() -> {
            eventRepository.deleteAll();
            notificatieRepository.deleteAll();
            entityManager.createNativeQuery("DELETE FROM bevestiging").executeUpdate();
        });
    }

    // Een Dienstverlener die de callback en de feed gebruikt, herkent hetzelfde event aan de envelop-id.
    @Test
    void wijzigingen_zelfdeEventInFeedEnCallback_heeftDezelfdeEnvelopId() {
        UUID notifyId = verzondenNotificatie("https://omc.example.nl/callback");
        receiptVerwerker.verwerk(notifyId, null, "delivered", OffsetDateTime.now(ZoneOffset.UTC));
        ArgumentCaptor<StatusUpdateOpdracht> opdracht = ArgumentCaptor.forClass(StatusUpdateOpdracht.class);
        verify(consumentCallbackAdapter).stuurStatusUpdate(opdracht.capture());

        List<String> ids = given()
                .when().get(FEED)
                .then()
                .statusCode(200)
                .body("events", hasSize(4))
                .body("events[3].type", equalTo("nl.overheid.moz.notificatie.status.bezorgd"))
                .body("events[3].sequence", equalTo("3"))
                .extract().path("events.id");

        assertEquals(String.valueOf(opdracht.getValue().eventId()), ids.get(3));
    }

    @Test
    void wijzigingen_metLimietEnCursor_leestVerder() {
        verzondenNotificatie(null);

        String cursor = given().queryParam("limiet", 2).when().get(FEED)
                .then().statusCode(200).body("events", hasSize(2)).extract().path("cursor");
        given().queryParam("cursor", cursor).when().get(FEED)
                .then().statusCode(200).body("events", hasSize(1));
    }

    @Test
    void wijzigingen_ongeldigeCursor_geeft400() {
        given().queryParam("cursor", "geen cursor !").when().get(FEED).then().statusCode(400);
    }

    @Test
    void wijzigingen_cursorUitAnderEpoch_geeft410MetEigenType() {
        given().queryParam("cursor", new Cursor(2, 1, 1).codeer()).when().get(FEED)
                .then()
                .statusCode(410)
                .body("type", equalTo(Problems.TYPE_CURSOR_VERVALLEN.toString()));
    }

    @Test
    void wijzigingen_aanroeplimietBereikt_geeft429MetEigenType() {
        when(aanroeplimiet.registreer(any())).thenReturn(false);

        given().when().get(FEED)
                .then()
                .statusCode(429)
                .body("type", equalTo(Problems.TYPE_AANROEPLIMIET_OVERSCHREDEN.toString()));
    }

    @Test
    void bevestiging_schrijftDeCursor() {
        verzondenNotificatie(null);
        String cursor = given().when().get(FEED).then().statusCode(200).extract().path("cursor");

        given().contentType(ContentType.JSON).body("{\"cursor\": \"" + cursor + "\"}")
                .when().put(BEVESTIGING).then().statusCode(204);

        Number aantal = (Number) QuarkusTransaction.requiringNew().call(() -> entityManager
                .createNativeQuery("SELECT COUNT(*) FROM bevestiging WHERE dv_id = ?1")
                .setParameter(1, NotificatieFixtures.DV_ID).getSingleResult());
        assertEquals(1, aantal.intValue());
    }

    @Test
    void bevestiging_zonderOfMetOngeldigeCursor_geeft400_enVervallen_geeft410() {
        given().contentType(ContentType.JSON).body("{\"cursor\": \" \"}").when().put(BEVESTIGING).then().statusCode(400);
        given().contentType(ContentType.JSON).when().put(BEVESTIGING).then().statusCode(400);
        given().contentType(ContentType.JSON).body("null").when().put(BEVESTIGING).then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"cursor\": \"geen cursor !\"}").when().put(BEVESTIGING).then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"cursor\": \"" + new Cursor(2, 1, 1).codeer() + "\"}")
                .when().put(BEVESTIGING)
                .then()
                .statusCode(410)
                .body("type", equalTo(Problems.TYPE_CURSOR_VERVALLEN.toString()));
    }

    private UUID verzondenNotificatie(String callbackUrl) {
        UUID notifyId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie notificatie = new Notificatie(NotificatieFixtures.DV_ID, callbackUrl);
            overgangsfunctie.neemAan(notificatie);
            overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.IN_VERZENDING, null);
            Poging poging = new Poging(notificatie.getId(), 1);
            pogingRepository.persist(poging);
            poging.markeerVerzonden(notifyId, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
            overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.VERZONDEN, null);
        });

        return notifyId;
    }
}
