package nl.rijksoverheid.moz.nmc.controller;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.FscIngeschakeldProfiel;
import nl.rijksoverheid.moz.nmc.service.toegang.FscSleutelsOnbereikbaarException;
import nl.rijksoverheid.moz.nmc.service.toegang.FscTokenValidator;
import nl.rijksoverheid.moz.nmc.testhelper.FscManagerStub;
import nl.rijksoverheid.moz.nmc.testhelper.FscManagerStub.Sleutel;
import nl.rijksoverheid.moz.nmc.testhelper.LeesHelper;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TaakFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TestDatabase;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/** De controle van het FSC-toegangstoken op de echte paden, met twee dienstverleners in het register. */
@QuarkusTest
@TestProfile(FscIngeschakeldProfiel.class)
class FscToegangFilterTest {

    private static final String DECENTRAAL = "/api/nmc/v1/decentraal/notificaties";
    private static final String FEED = "/api/nmc/v1/notificaties/events";
    private static final String CONTACTHERSTEL = "/api/nmc/v1/contactherstellen/" + UUID.randomUUID();

    private static final UUID DV_A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID DV_B = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final String OIN_A = "00000001111111111000";
    private static final String OIN_B = "00000002222222222000";
    private static final String OIN_ONBEKEND = "00000003333333333000";
    private static final String API_VERSIE = ConfigProvider.getConfig().getValue("mp.openapi.info.version", String.class);

    private static final String AANVRAAG = """
            {
              "emailAdres": "burger@example.nl",
              "berichtType": "Stuurgroep Agenda",
              "berichtgegevens": { "naam": "Voorbeeld BV" }
            }
            """;

    @ConfigProperty(name = FscIngeschakeldProfiel.PRIVATE_SLEUTEL)
    String privateSleutel;

    @Inject
    EntityManager entityManager;

    @InjectSpy
    FscTokenValidator validator;

    private Sleutel sleutel;

    @BeforeEach
    void setUp() {
        sleutel = Sleutel.rsaUitPrivaat(FscIngeschakeldProfiel.DUIM, privateSleutel);
        TestDatabase.leeg();
        QuarkusTransaction.requiringNew().run(() -> {
            TaakFixtures.voegDienstverlenerToe(entityManager, DV_A, OIN_A, "Dienstverlener A");
            TaakFixtures.voegDienstverlenerToe(entityManager, DV_B, OIN_B, "Dienstverlener B");
        });
    }

    @Test
    void aanname_metTokenVanEenDienstverlenerUitHetRegister_koppeltDeNotificatieAanDieDienstverlener() {
        UUID vanA = neemAan(OIN_A);
        UUID vanB = neemAan(OIN_B);

        assertEquals(DV_A, LeesHelper.notificatie(vanA).getDvId());
        assertEquals(DV_B, LeesHelper.notificatie(vanB).getDvId());
    }

    @Test
    void aanname_tokenMetBearerVooraf_wordtAangenomen() {
        given().header(FscToegangFilter.HEADER, "Bearer " + token(OIN_A))
                .contentType(ContentType.JSON).body(AANVRAAG)
                .when().post(DECENTRAAL)
                .then().statusCode(202);
    }

    @ParameterizedTest
    @ValueSource(strings = {DECENTRAAL, "/api/nmc/v1/centraal/notificaties", "/api/nmc/v1/contactherstellen"})
    void aanname_zonderToken_geeft401EnSlaatNietsOp(String pad) {
        given().contentType(ContentType.JSON).body(AANVRAAG)
                .when().post(pad)
                .then().statusCode(401)
                .contentType("application/problem+json")
                .header("WWW-Authenticate", "Bearer")
                .header("API-Version", API_VERSIE);

        assertEquals(0, LeesHelper.aantalNotificaties());
    }

    @ParameterizedTest
    @ValueSource(strings = {FEED, "/api/nmc/v1/contactherstellen/3f0e7a52-6f0b-4d0e-9d8c-1c2b3a4d5e6f"})
    void lezen_zonderToken_geeft401(String pad) {
        given().when().get(pad).then().statusCode(401);
    }

    @Test
    void bevestigen_zonderToken_geeft401() {
        given().contentType(ContentType.JSON).body("{\"cursor\": \"x\"}")
                .when().put(FEED + "/bevestiging")
                .then().statusCode(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "geen-token", "Bearer ", "Bearer geen-token"})
    void aanname_headerZonderBruikbaarToken_geeft401(String header) {
        given().header(FscToegangFilter.HEADER, header).contentType(ContentType.JSON).body(AANVRAAG)
                .when().post(DECENTRAAL)
                .then().statusCode(401);

        assertEquals(0, LeesHelper.aantalNotificaties());
    }

    @Test
    void aanname_tokenVanEenAndereSleutel_geeft401ZonderDeRedenTeNoemen() {
        String vervalst = sleutel.vervalsing().token(FscManagerStub.claims(OIN_A, Instant.now()));

        given().header(FscToegangFilter.HEADER, vervalst).contentType(ContentType.JSON).body(AANVRAAG)
                .when().post(DECENTRAAL)
                .then().statusCode(401)
                .body("detail", not(containsString("handtekening")));

        assertEquals(0, LeesHelper.aantalNotificaties());
    }

    @Test
    void aanname_verlopenToken_geeft401() {
        JwtClaims claims = FscManagerStub.claims(OIN_A, Instant.now().minusSeconds(3600));

        given().header(FscToegangFilter.HEADER, sleutel.token(claims)).contentType(ContentType.JSON).body(AANVRAAG)
                .when().post(DECENTRAAL)
                .then().statusCode(401);
    }

    @Test
    void aanname_oinDatNietInHetRegisterStaat_geeft403EnSlaatNietsOp() {
        metToken(OIN_ONBEKEND).contentType(ContentType.JSON).body(AANVRAAG)
                .when().post(DECENTRAAL)
                .then().statusCode(403)
                .contentType("application/problem+json")
                .header("API-Version", API_VERSIE);

        assertEquals(0, LeesHelper.aantalNotificaties());
    }

    // De dienstverlener uit nmc.dienstverlener.id is er alleen voor een omgeving zonder tokencontrole.
    @Test
    void aanname_oinVanDeGeconfigureerdeDienstverlener_telt_alsElkAnderOin() {
        UUID id = neemAan("00000000000000000000");

        assertEquals(NotificatieFixtures.DV_ID, LeesHelper.notificatie(id).getDvId());
    }

    @Test
    void eventfeed_toontAlleenDeEventsVanDeEigenDienstverlener() {
        neemAan(OIN_A);

        metToken(OIN_A).when().get(FEED).then().statusCode(200).body("events", hasSize(1));
        metToken(OIN_B).when().get(FEED).then().statusCode(200).body("events", hasSize(0));
    }

    @Test
    void contactherstel_metToken_bereiktDeController() {
        metToken(OIN_A).when().get(CONTACTHERSTEL).then().statusCode(501);
    }

    @Test
    void aanname_jwksNietOpTeHalen_geeft503() {
        doThrow(new FscSleutelsOnbereikbaarException("storing", new IOException("geen verbinding")))
                .when(validator).valideer(anyString());

        metToken(OIN_A).contentType(ContentType.JSON).body(AANVRAAG)
                .when().post(DECENTRAAL)
                .then().statusCode(503)
                .contentType("application/problem+json")
                .header("API-Version", API_VERSIE);

        assertEquals(0, LeesHelper.aantalNotificaties());
    }

    // De ontvanger van een webhook haalt de sleutels op zonder FSC.
    @Test
    void jwksVanDeWebhook_zonderToken_blijftOpenbaar() {
        given().when().get("/api/nmc/v1/.well-known/jwks.json").then().statusCode(200).body("keys", hasSize(1));
    }

    // NotifyNL roept de callback aan vanaf internet, met het eigen bearer-token en zonder FSC.
    @Test
    void notifyNLCallback_zonderFscToken_wordtAlleenOpHetEigenTokenGetoetst() {
        String receipt = "{\"id\": \"" + UUID.randomUUID() + "\", \"status\": \"delivered\"}";

        given().contentType(ContentType.JSON).body(receipt)
                .header("Authorization", "Bearer test-callback-token-niet-voor-productie")
                .when().post("/api/nmc/v1/notifynl-callback")
                .then().statusCode(204);
        given().contentType(ContentType.JSON).body(receipt)
                .when().post("/api/nmc/v1/notifynl-callback")
                .then().statusCode(401)
                .body("detail", equalTo("Geldig bearer token vereist"));
    }

    private UUID neemAan(String oin) {
        String id = metToken(oin).contentType(ContentType.JSON).body(AANVRAAG)
                .when().post(DECENTRAAL)
                .then().statusCode(202)
                .extract().path("notificatieId");

        return UUID.fromString(id);
    }

    private RequestSpecification metToken(String oin) {
        return given().header(FscToegangFilter.HEADER, token(oin));
    }

    private String token(String oin) {
        return sleutel.token(FscManagerStub.claims(oin, Instant.now()));
    }
}
