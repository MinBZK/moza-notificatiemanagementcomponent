package nl.rijksoverheid.moz.nmc.controller;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Ontvanger;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.service.aanname.AannameService;
import nl.rijksoverheid.moz.nmc.service.sleutel.Sleutelbeheer;
import nl.rijksoverheid.moz.nmc.testhelper.LeesHelper;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TaakFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TestDatabase;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** De intake is een aanname: 202 na de commit, zonder aanroep van NotifyNL. */
@QuarkusTest
class DecentraleNotificatieControllerTest {

    private static final String PAD = "/api/nmc/v1/decentraal/notificaties";

    @InjectMock
    @RestClient
    SendAMessageApi sendAMessageApi;

    // Zonder stubbing de echte service; één test laat hem een onverwachte fout gooien.
    @InjectSpy
    AannameService aannameService;

    @Inject
    Sleutelbeheer sleutelbeheer;

    @Inject
    EntityManager entityManager;

    @BeforeEach
    void setUp() {
        TestDatabase.leeg();
    }

    @AfterEach
    void quotumWeg() {
        zetQuotum(null);
    }

    @Test
    void decentraleNotificatieVersturen_happyFlow_retourneert202EnBewaartHetAdresVersleuteld() {
        String id = given().contentType(ContentType.JSON)
                .body(aanvraag("burger@example.nl", "Stuurgroep Agenda", ""))
                .when()
                .post(PAD)
                .then()
                .statusCode(202)
                .body("notificatieId", notNullValue())
                .extract()
                .path("notificatieId");

        Notificatie notificatie = LeesHelper.notificatie(UUID.fromString(id));
        assertEquals(NotificatieStatus.AANGENOMEN, notificatie.getStatus());
        assertEquals(
                Ontvanger.email("burger@example.nl"),
                sleutelbeheer.ontsleutelOntvanger(notificatie.getId(), notificatie.getVersleuteldeGegevens()));
        assertEquals(
                UUID.fromString(id), LeesHelper.enigeTaak(TaakSoort.VERZENDEN).getNotificatieId());
        verifyNoInteractions(sendAMessageApi);
    }

    @Test
    void decentraleNotificatieVersturen_ongeldigEmailAdres_retourneert400() {
        given().contentType(ContentType.JSON)
                .body(aanvraag("geen-adres", "Stuurgroep Agenda", ""))
                .when()
                .post(PAD)
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("violations.field", hasItem(containsString("emailAdres")));
    }

    @Test
    void decentraleNotificatieVersturen_quotumBereikt_retourneert429MetEigenType() {
        zetQuotum(1);
        given().contentType(ContentType.JSON)
                .body(aanvraag("burger@example.nl", "Stuurgroep Agenda", ""))
                .when()
                .post(PAD)
                .then()
                .statusCode(202);

        given().contentType(ContentType.JSON)
                .body(aanvraag("burger@example.nl", "Stuurgroep Agenda", ""))
                .when()
                .post(PAD)
                .then()
                .statusCode(429)
                .contentType("application/problem+json")
                .body("type", equalTo("https://mijnoverheidzakelijk.nl/nmc/problemen/quotum-overschreden"));
    }

    // Een onverwachte fout wordt een 500 zonder de interne melding.
    @Test
    void decentraleNotificatieVersturen_onverwachteFout_retourneert500ZonderInterneMelding() {
        doThrow(new IllegalStateException("interne melding"))
                .when(aannameService)
                .neemAan(any(), any());

        given().contentType(ContentType.JSON)
                .body(aanvraag("burger@example.nl", "Stuurgroep Agenda", ""))
                .when()
                .post(PAD)
                .then()
                .statusCode(500)
                .contentType("application/problem+json")
                .body("title", equalTo("Aannamefout"))
                .body("detail", equalTo("Er kan momenteel geen notificatie worden aangenomen"));
    }

    private static String aanvraag(String emailAdres, String berichtType, String extraVeld) {
        return """
                {
                  "emailAdres": "%s",
                  %s
                  "berichtType": "%s",
                  "berichtgegevens": { "naam": "Voorbeeld BV" }
                }
                """.formatted(emailAdres, extraVeld, berichtType);
    }

    private void zetQuotum(Integer quotum) {
        QuarkusTransaction.requiringNew()
                .run(() -> TaakFixtures.zetQuotumPerDag(entityManager, NotificatieFixtures.DV_ID, quotum));
    }
}
