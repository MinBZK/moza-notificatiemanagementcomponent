package nl.rijksoverheid.moz.nmc.controller;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Ontvanger;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.service.Sleutelbeheer;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;

/** De intake is een aanname: 202 na de commit, zonder aanroep van NotifyNL. */
@QuarkusTest
class DecentraleNotificatieControllerTest {

    private static final String PAD = "/api/nmc/v1/decentraal/notificaties";

    @InjectMock
    @RestClient
    SendAMessageApi sendAMessageApi;

    @Inject
    NotificatieRepository notificatieRepository;

    @Inject
    TaakRepository taakRepository;

    @Inject
    Sleutelbeheer sleutelbeheer;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            taakRepository.deleteAll();
            notificatieRepository.deleteAll();
        });
    }

    @Test
    void decentraleNotificatieVersturen_happyFlow_retourneert202EnBewaartHetAdresVersleuteld() {
        String id = given()
                .contentType(ContentType.JSON)
                .body(aanvraag("burger@example.nl", "Stuurgroep Agenda", "\"callbackUrl\": \"https://omc.example.nl/cb\","))
                .when().post(PAD)
                .then()
                .statusCode(202)
                .header("Location", startsWith("/api/nmc/v1/notificaties/"))
                .body("notificatieId", notNullValue())
                .extract().path("notificatieId");

        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie notificatie = notificatieRepository.findById(UUID.fromString(id));
            assertEquals(NotificatieStatus.AANGENOMEN, notificatie.getStatus());
            assertEquals("https://omc.example.nl/cb", notificatie.getCallbackUrl());
            assertEquals(Ontvanger.email("burger@example.nl"),
                    sleutelbeheer.ontsleutelOntvanger(notificatie.getId(), notificatie.getVersleuteldeGegevens()));
            assertEquals(TaakSoort.VERZENDEN, taakRepository.listAll().getFirst().getSoort());
        });
        verifyNoInteractions(sendAMessageApi);
    }

    @Test
    void decentraleNotificatieVersturen_ongeldigeCallbackUrl_retourneert400() {
        given()
                .contentType(ContentType.JSON)
                .body(aanvraag("burger@example.nl", "Stuurgroep Agenda", "\"callbackUrl\": \"http://127.0.0.1/cb\","))
                .when().post(PAD)
                .then()
                .statusCode(400)
                .body("violations", notNullValue());
    }

    @Test
    void decentraleNotificatieVersturen_ontbrekendEmailAdres_retourneert400() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        { "berichtType": "Stuurgroep Agenda" }
                        """)
                .when().post(PAD)
                .then()
                .statusCode(400);
    }

    @Test
    void decentraleNotificatieVersturen_ongeldigEmailAdres_retourneert400() {
        given()
                .contentType(ContentType.JSON)
                .body(aanvraag("geen-adres", "Stuurgroep Agenda", ""))
                .when().post(PAD)
                .then()
                .statusCode(400);
    }

    @Test
    void decentraleNotificatieVersturen_onbekendBerichtType_retourneert400() {
        given()
                .contentType(ContentType.JSON)
                .body(aanvraag("burger@example.nl", "Bestaat Niet", ""))
                .when().post(PAD)
                .then()
                .statusCode(400)
                .body("title", equalTo("Notificatie niet aangenomen."));
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
}
