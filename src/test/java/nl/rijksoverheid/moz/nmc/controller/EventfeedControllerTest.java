package nl.rijksoverheid.moz.nmc.controller;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.domain.Cursor;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import nl.rijksoverheid.moz.nmc.repository.BevestigingRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.service.Aanroeplimiet;
import nl.rijksoverheid.moz.nmc.service.Overgangsfunctie;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
class EventfeedControllerTest {

    private static final String FEED = "/api/nmc/v1/notificaties/events";
    private static final String BEVESTIGING = FEED + "/bevestiging";

    @InjectMock
    Aanroeplimiet aanroeplimiet;

    @Inject
    Overgangsfunctie overgangsfunctie;

    @Inject
    PogingRepository pogingRepository;

    @Inject
    BevestigingRepository bevestigingRepository;

    @BeforeEach
    void setUp() {
        when(aanroeplimiet.registreer(any())).thenReturn(true);
        TestDatabase.leeg();
    }

    @Test
    void events_metLimietEnCursor_leestVerder() {
        verzondenNotificatie();

        String cursor = given().queryParam("limiet", 2).when().get(FEED)
                .then().statusCode(200).body("events", hasSize(2)).extract().path("cursor");
        given().queryParam("cursor", cursor).when().get(FEED)
                .then().statusCode(200).body("events", hasSize(1));
    }

    @Test
    void events_ongeldigeCursor_geeft400() {
        given().queryParam("cursor", "geen cursor !").when().get(FEED).then().statusCode(400);
    }

    @Test
    void events_cursorUitAnderEpoch_geeft410MetEigenType() {
        given().queryParam("cursor", new Cursor(2, 1, 1).codeer()).when().get(FEED)
                .then()
                .statusCode(410)
                .body("type", equalTo(Problems.TYPE_CURSOR_VERVALLEN.toString()));
    }

    // Een ander epoch en een positie boven de jongste transactie geven hetzelfde antwoord, zonder het
    // epoch of de grens van het log te noemen.
    @ParameterizedTest
    @CsvSource({"2, 1", "1, 9223372036854775806"})
    void events_vervallenCursor_noemtDeRedenNiet(int epoch, long transactieId) {
        given().queryParam("cursor", new Cursor(epoch, transactieId, 1).codeer()).when().get(FEED)
                .then()
                .statusCode(410)
                .body("detail", equalTo(EventfeedController.CURSOR_VERVALLEN_DETAIL));
    }

    @Test
    void events_aanroeplimietBereikt_geeft429MetEigenTypeEnRetryAfter() {
        when(aanroeplimiet.registreer(any())).thenReturn(false);
        when(aanroeplimiet.secondenTotVolgendVenster()).thenReturn(17);

        given().when().get(FEED)
                .then()
                .statusCode(429)
                .header("Retry-After", "17")
                .body("type", equalTo(Problems.TYPE_AANROEPLIMIET_OVERSCHREDEN.toString()));
    }

    @Test
    void bevestiging_schrijftDeCursor() {
        verzondenNotificatie();
        String cursor = given().when().get(FEED).then().statusCode(200).extract().path("cursor");

        given().contentType(ContentType.JSON).body("{\"cursor\": \"" + cursor + "\"}")
                .when().put(BEVESTIGING).then().statusCode(204);

        assertTrue(QuarkusTransaction.requiringNew()
                .call(() -> bevestigingRepository.findByIdOptional(NotificatieFixtures.DV_ID).isPresent()));
    }

    @Test
    void bevestiging_aanroeplimietBereikt_geeft429MetRetryAfterEnSchrijftNiets() {
        when(aanroeplimiet.registreer(any())).thenReturn(false);
        when(aanroeplimiet.secondenTotVolgendVenster()).thenReturn(17);

        given().contentType(ContentType.JSON).body("{\"cursor\": \"" + new Cursor(1, 1, 1).codeer() + "\"}")
                .when().put(BEVESTIGING)
                .then()
                .statusCode(429)
                .header("Retry-After", "17")
                .body("type", equalTo(Problems.TYPE_AANROEPLIMIET_OVERSCHREDEN.toString()));

        assertTrue(QuarkusTransaction.requiringNew()
                .call(() -> bevestigingRepository.findByIdOptional(NotificatieFixtures.DV_ID).isEmpty()));
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
                .body("type", equalTo(Problems.TYPE_CURSOR_VERVALLEN.toString()))
                .body("detail", equalTo(EventfeedController.CURSOR_VERVALLEN_DETAIL));
    }

    private UUID verzondenNotificatie() {
        UUID notifyId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie notificatie = new Notificatie(NotificatieFixtures.DV_ID, OffsetDateTime.now());
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
