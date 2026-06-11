package nl.rijksoverheid.moz.controller;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.common.IdentificatieType;
import nl.rijksoverheid.moz.common.NotificatieStatus;
import nl.rijksoverheid.moz.common.ProfielType;
import nl.rijksoverheid.moz.common.VerzendingType;
import nl.rijksoverheid.moz.entity.Notificatie;
import nl.rijksoverheid.moz.entity.Verzending;
import nl.rijksoverheid.moz.repository.NotificatieRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class NotificatieControllerTest {

    @Inject
    NotificatieRepository notificatieRepository;

    @Test
    void notificatieAanmaken_centraal_retourneert201() {
        String aanvraag = """
                {
                  "identificatieType": "BSN",
                  "identificatieNummer": "123456789",
                  "dienstverlener": "Gemeente Voorbeeld",
                  "dienst": "Parkeervergunning",
                  "berichtType": "aanvraag-ontvangen",
                  "profielType": "CENTRAAL",
                  "referentie": "OMC-12345"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(aanvraag)
                .when().post("/api/nmc/v1/notificaties")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("profielType", equalTo("CENTRAAL"))
                .body("status", equalTo("CREATED"))
                .body("verzendingen", hasSize(1))
                .body("verzendingen[0].type", equalTo("PRIMAIR"))
                .body("verzendingen[0].kanaal", nullValue());
    }

    @Test
    void notificatieAanmaken_decentraalMetEmail_retourneert201() {
        String aanvraag = """
                {
                  "identificatieType": "BSN",
                  "identificatieNummer": "123456789",
                  "dienstverlener": "Gemeente Voorbeeld",
                  "dienst": "Parkeervergunning",
                  "berichtType": "aanvraag-ontvangen",
                  "profielType": "DECENTRAAL",
                  "verzendKanaal": "EMAIL",
                  "ontvangerEmail": "burger@example.nl"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(aanvraag)
                .when().post("/api/nmc/v1/notificaties")
                .then()
                .statusCode(201)
                .body("verzendingen[0].kanaal", equalTo("EMAIL"));
    }

    @Test
    void notificatieAanmaken_decentraalZonderVerzendKanaal_retourneert400() {
        String aanvraag = """
                {
                  "identificatieType": "BSN",
                  "identificatieNummer": "123456789",
                  "dienstverlener": "Gemeente Voorbeeld",
                  "dienst": "Parkeervergunning",
                  "berichtType": "aanvraag-ontvangen",
                  "profielType": "DECENTRAAL"
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
    void notificatieOphalen_onbekendId_retourneert404() {
        given()
                .when().get("/api/nmc/v1/notificaties/" + UUID.randomUUID())
                .then()
                .statusCode(404);
    }

    @Test
    void notificatieOphalen_bestaandId_retourneert200() {
        String id = notificatieAanmaken("""
                {
                  "identificatieType": "KVK",
                  "identificatieNummer": "12345678",
                  "dienstverlener": "Gemeente Voorbeeld",
                  "dienst": "Parkeervergunning",
                  "berichtType": "aanvraag-ontvangen",
                  "profielType": "CENTRAAL"
                }
                """);

        given()
                .when().get("/api/nmc/v1/notificaties/" + id)
                .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("dienst", equalTo("Parkeervergunning"));
    }

    @Test
    void contactherstelInitieren_retourneert201() {
        String id = notificatieAanmaken("""
                {
                  "identificatieType": "BSN",
                  "identificatieNummer": "123456789",
                  "dienstverlener": "Gemeente Voorbeeld",
                  "dienst": "Parkeervergunning",
                  "berichtType": "aanvraag-ontvangen",
                  "profielType": "CENTRAAL"
                }
                """);

        String contactherstel = """
                {
                  "kanaal": "EMAIL",
                  "ontvangerEmail": "burger@example.nl"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(contactherstel)
                .when().post("/api/nmc/v1/notificaties/" + id + "/contactherstel")
                .then()
                .statusCode(201)
                .body("type", equalTo("CONTACTHERSTEL"))
                .body("kanaal", equalTo("EMAIL"))
                .body("status", equalTo("CREATED"));
    }

    @Test
    void contactherstelInitieren_onbekendeNotificatie_retourneert404() {
        String contactherstel = """
                {
                  "kanaal": "EMAIL",
                  "ontvangerEmail": "burger@example.nl"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(contactherstel)
                .when().post("/api/nmc/v1/notificaties/" + UUID.randomUUID() + "/contactherstel")
                .then()
                .statusCode(404);
    }

    @Test
    void contactherstelInitieren_emailZonderOntvanger_retourneert400() {
        String id = notificatieAanmaken("""
                {
                  "identificatieType": "BSN",
                  "identificatieNummer": "123456789",
                  "dienstverlener": "Gemeente Voorbeeld",
                  "dienst": "Parkeervergunning",
                  "berichtType": "aanvraag-ontvangen",
                  "profielType": "CENTRAAL"
                }
                """);

        String contactherstel = """
                {
                  "kanaal": "EMAIL"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(contactherstel)
                .when().post("/api/nmc/v1/notificaties/" + id + "/contactherstel")
                .then()
                .statusCode(400);
    }

    @Test
    void notifyCallback_bestaandeReferentie_werktStatusBij() {
        UUID notifyReferentie = UUID.randomUUID();
        AtomicReference<UUID> notificatieId = new AtomicReference<>();

        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie notificatie = new Notificatie();
            notificatie.setProfielType(ProfielType.CENTRAAL);
            notificatie.setIdentificatieType(IdentificatieType.BSN);
            notificatie.setIdentificatieNummer("123456789");
            notificatie.setDienstverlener("Gemeente Voorbeeld");
            notificatie.setDienst("Parkeervergunning");
            notificatie.setBerichtType("aanvraag-ontvangen");

            Verzending verzending = new Verzending();
            verzending.setType(VerzendingType.PRIMAIR);
            verzending.setStatus(NotificatieStatus.SENDING);
            verzending.setNotifyReferentie(notifyReferentie);

            notificatie.addVerzending(verzending);
            notificatieRepository.persist(notificatie);

            notificatieId.set(notificatie.id);
        });

        String callback = """
                {
                  "id": "%s",
                  "status": "delivered"
                }
                """.formatted(notifyReferentie);

        given()
                .contentType(ContentType.JSON)
                .body(callback)
                .when().post("/api/nmc/v1/notify-callback")
                .then()
                .statusCode(200);

        given()
                .when().get("/api/nmc/v1/notificaties/" + notificatieId.get())
                .then()
                .statusCode(200)
                .body("status", equalTo("DELIVERED"))
                .body("verzendingen[0].status", equalTo("DELIVERED"));
    }

    @Test
    void notifyCallback_onbekendeReferentie_retourneert404() {
        String callback = """
                {
                  "id": "%s",
                  "status": "delivered"
                }
                """.formatted(UUID.randomUUID());

        given()
                .contentType(ContentType.JSON)
                .body(callback)
                .when().post("/api/nmc/v1/notify-callback")
                .then()
                .statusCode(404);
    }

    private String notificatieAanmaken(String aanvraag) {
        return given()
                .contentType(ContentType.JSON)
                .body(aanvraag)
                .when().post("/api/nmc/v1/notificaties")
                .then()
                .statusCode(201)
                .extract().path("id");
    }
}
