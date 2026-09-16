package nl.rijksoverheid.moz.nmc;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bewaakt dat de statuswaarden die een Dienstverlener in de callback kan krijgen ook echt in het
 * gepubliceerde document staan.
 * <p>
 * Toetst tegen {@code /q/openapi} en niet tegen {@code META-INF/openapi.yaml}: dat de waarden in
 * het bronbestand staan zegt niets over wat een afnemer krijgt. SmallRye bouwt het gepubliceerde
 * document op uit die bron, en een filter of een herschikking daar kan onderdelen laten vervallen
 * zonder dat het bestand verandert.
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
