package nl.rijksoverheid.moz.nmc;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bewaakt dat de statuswaarden die een Dienstverlener in de callback kan krijgen ook echt in het
 * gepubliceerde document staan.
 * <p>
 * Niet vanzelfsprekend: SmallRye laat het {@code callbacks}-blok uit {@code META-INF/openapi.yaml}
 * vallen bij het bouwen van de applicatie en snoeit de schema's waar het naar verwijst daarna weg
 * als ongebruikt. Gecontroleerd op het previewcluster: {@code NotificatieStatusEvent} en de
 * statusenum stonden daar niet in {@code /q/openapi}. De waarden staan daarom óók in de
 * beschrijving van {@code callbackUrl}, die wél overleeft — en deze test bewaakt dat, want de
 * bronspec alleen bekijken geeft een vals positief.
 */
@QuarkusTest
class GepubliceerdeSpecTest {

    @Test
    void deStatuswaardenStaanInHetGepubliceerdeDocument() {
        String spec = given().when().get("/q/openapi").then().statusCode(200).extract().asString();

        for (String status : new String[] {"delivered", "permanent-failure", "temporary-failure",
                "technical-failure", "sending", "created", "onbekend"}) {
            assertTrue(spec.contains(status), "status '" + status + "' hoort in het gepubliceerde document");
        }
        assertTrue(spec.contains("niet als eindstatus"),
                "de toelichting bij onbekend hoort in het gepubliceerde document");
    }
}
