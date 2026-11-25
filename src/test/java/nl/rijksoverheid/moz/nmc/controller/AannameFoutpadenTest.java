package nl.rijksoverheid.moz.nmc.controller;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.http.ContentType;
import nl.rijksoverheid.moz.nmc.service.aanname.AannameService;
import nl.rijksoverheid.moz.nmc.testhelper.LeesHelper;
import nl.rijksoverheid.moz.nmc.testhelper.TaakFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TestDatabase;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * De foutpaden die de centrale en de decentrale aanname elk apart dragen: een eigen catch in de
 * controller en eigen grenzen in het schema. Elke test draait daarom over beide paden.
 */
@QuarkusTest
class AannameFoutpadenTest {

    private static final String PROBLEM_JSON = "application/problem+json";
    // De versie uit het contract; ook een foutantwoord draagt hem.
    private static final String API_VERSIE = ConfigProvider.getConfig().getValue("mp.openapi.info.version", String.class);

    enum Intake {
        CENTRAAL("/api/nmc/v1/centraal/notificaties", "identificatieNummer", """
                "identificatieType": "KVK",
                "identificatieNummer": "12345678",
                "dienstverlener": "Gemeente Voorbeeld","""),
        DECENTRAAL("/api/nmc/v1/decentraal/notificaties", "emailAdres", """
                "emailAdres": "burger@example.nl",""");

        private final String pad;
        private final String verplichtVeld;
        private final String ontvangerVelden;

        Intake(String pad, String verplichtVeld, String ontvangerVelden) {
            this.pad = pad;
            this.verplichtVeld = verplichtVeld;
            this.ontvangerVelden = ontvangerVelden;
        }

        String aanvraag(String berichtType, String berichtgegevens) {
            return "{ %s \"berichtType\": \"%s\", \"berichtgegevens\": %s }".formatted(ontvangerVelden, berichtType, berichtgegevens);
        }

        String aanvraagMetDvRef(String dvRef) {
            return "{ %s \"dvRef\": \"%s\", \"berichtType\": \"Stuurgroep Agenda\", \"berichtgegevens\": { \"naam\": \"Voorbeeld BV\" } }"
                    .formatted(ontvangerVelden, dvRef);
        }

        String aanvraag() {
            return aanvraag("Stuurgroep Agenda", "{ \"naam\": \"Voorbeeld BV\" }");
        }

        String aanvraagZonderVerplichtVeld() {
            return "{ \"identificatieType\": \"KVK\", \"dienstverlener\": \"Gemeente Voorbeeld\", \"berichtType\": \"Stuurgroep Agenda\" }";
        }
    }

    @InjectSpy
    AannameService aannameService;

    @BeforeEach
    void setUp() {
        TestDatabase.leeg();
    }

    @AfterEach
    void quotumWeg() {
        TaakFixtures.zetQuotumPerDag(null);
    }

    @ParameterizedTest
    @EnumSource(Intake.class)
    void aanname_quotumBereikt_retourneert429MetEigenType(Intake intake) {
        TaakFixtures.zetQuotumPerDag(1);
        given().contentType(ContentType.JSON).body(intake.aanvraag()).when().post(intake.pad).then().statusCode(202);

        given()
                .contentType(ContentType.JSON)
                .body(intake.aanvraag())
                .when().post(intake.pad)
                .then()
                .statusCode(429)
                .header("API-Version", API_VERSIE)
                .contentType(PROBLEM_JSON)
                .body("type", equalTo("https://mijnoverheidzakelijk.nl/nmc/problemen/quotum-overschreden"));

        assertEquals(1, LeesHelper.aantalNotificaties());
    }

    @ParameterizedTest
    @EnumSource(Intake.class)
    void aanname_meerDan50Berichtgegevens_retourneert400OpBerichtgegevens(Intake intake) {
        StringBuilder gegevens = new StringBuilder("{");
        for (int i = 1; i <= 51; i++) {
            gegevens.append(i > 1 ? ", " : "").append("\"v").append(i).append("\": \"x\"");
        }
        gegevens.append("}");

        weigertOpVeld(intake, intake.aanvraag("Stuurgroep Agenda", gegevens.toString()), "berichtgegevens");
    }

    @ParameterizedTest
    @EnumSource(Intake.class)
    void aanname_berichtgegevenLangerDan2000Tekens_retourneert400OpBerichtgegevens(Intake intake) {
        String gegevens = "{ \"naam\": \"" + "x".repeat(2001) + "\" }";

        weigertOpVeld(intake, intake.aanvraag("Stuurgroep Agenda", gegevens), "berichtgegevens");
    }

    @ParameterizedTest
    @EnumSource(Intake.class)
    void aanname_naamVanBerichtgegevenLangerDan100Tekens_retourneert400OpBerichtgegevens(Intake intake) {
        String gegevens = "{ \"" + "n".repeat(101) + "\": \"Voorbeeld BV\" }";

        weigertOpVeld(intake, intake.aanvraag("Stuurgroep Agenda", gegevens), "berichtgegevens");
    }

    @ParameterizedTest
    @EnumSource(Intake.class)
    void aanname_verplichtVeldVanDeOntvangerOntbreekt_retourneert400OpDatVeld(Intake intake) {
        given()
                .contentType(ContentType.JSON)
                .body(intake.aanvraagZonderVerplichtVeld())
                .when().post(intake.pad)
                .then()
                .statusCode(400)
                .contentType(PROBLEM_JSON)
                .body("field", equalTo(intake.verplichtVeld));

        assertEquals(0, LeesHelper.aantalNotificaties());
    }

    @ParameterizedTest
    @EnumSource(Intake.class)
    void aanname_onbekendBerichtType_retourneert400AlsProblem(Intake intake) {
        given()
                .contentType(ContentType.JSON)
                .body(intake.aanvraag("Bestaat Niet", "{}"))
                .when().post(intake.pad)
                .then()
                .statusCode(400)
                .contentType(PROBLEM_JSON)
                .body("title", equalTo("Notificatie niet aangenomen."))
                .body("detail", containsString("Bestaat Niet"));

        assertEquals(0, LeesHelper.aantalNotificaties());
    }

    @ParameterizedTest
    @EnumSource(Intake.class)
    void aanname_zelfdeDvRefEnInhoudOpnieuw_retourneert202MetHetzelfdeNotificatieId(Intake intake) {
        String eerste = given().contentType(ContentType.JSON).body(intake.aanvraagMetDvRef("order-1"))
                .when().post(intake.pad).then().statusCode(202).extract().path("notificatieId");

        String tweede = given().contentType(ContentType.JSON).body(intake.aanvraagMetDvRef("order-1"))
                .when().post(intake.pad).then().statusCode(202).extract().path("notificatieId");
        String andere = given().contentType(ContentType.JSON).body(intake.aanvraagMetDvRef("order-2"))
                .when().post(intake.pad).then().statusCode(202).extract().path("notificatieId");

        assertEquals(eerste, tweede);
        assertNotEquals(eerste, andere);
        assertEquals(2, LeesHelper.aantalNotificaties());
    }

    @ParameterizedTest
    @EnumSource(Intake.class)
    void aanname_dvRefLeegOfLangerDan100Tekens_retourneert400OpDvRef(Intake intake) {
        weigertOpVeld(intake, intake.aanvraagMetDvRef(""), "dvRef");
        weigertOpVeld(intake, intake.aanvraagMetDvRef("r".repeat(101)), "dvRef");

        given().contentType(ContentType.JSON).body(intake.aanvraagMetDvRef("r".repeat(100)))
                .when().post(intake.pad).then().statusCode(202);
    }

    // De melding van de oorzaak kan gegevens uit de aanvraag of de database bevatten en gaat niet mee.
    @ParameterizedTest
    @EnumSource(Intake.class)
    void aanname_onverwachteFout_retourneert500ZonderDeOorzaakTeNoemen(Intake intake) {
        doThrow(new IllegalStateException("verbinding met db-host-1 verbroken")).when(aannameService).neemAan(any(), any());

        given()
                .contentType(ContentType.JSON)
                .body(intake.aanvraag())
                .when().post(intake.pad)
                .then()
                .statusCode(500)
                .header("API-Version", API_VERSIE)
                .contentType(PROBLEM_JSON)
                .body("title", equalTo("Aannamefout"))
                .body("detail", equalTo("Er kan momenteel geen notificatie worden aangenomen"))
                .body(not(containsString("db-host-1")));
    }

    private static void weigertOpVeld(Intake intake, String aanvraag, String veld) {
        given()
                .contentType(ContentType.JSON)
                .body(aanvraag)
                .when().post(intake.pad)
                .then()
                .statusCode(400)
                .contentType(PROBLEM_JSON)
                .body("violations.field", hasItem(containsString(veld)));

        assertEquals(0, LeesHelper.aantalNotificaties());
    }
}
