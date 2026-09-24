package nl.rijksoverheid.moz.nmc;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Reden;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Bewaakt dat de statuswaarden en redenen die een Dienstverlener in de callback kan krijgen als echte
 * enum in het gepubliceerde document staan.
 * <p>
 * Toetst tegen {@code /q/openapi} en niet tegen {@code META-INF/openapi.yaml}: wat in de bron staat
 * zegt niets over wat een afnemer krijgt.
 */
@QuarkusTest
class GepubliceerdeSpecTest {

    private JsonPath spec;

    @BeforeEach
    void haalSpecOp() {
        spec = given()
                .queryParam("format", "JSON")
                .when().get("/q/openapi")
                .then().statusCode(200)
                .extract().jsonPath();
    }

    // Gesorteerd vergeleken: alleen het lidmaatschap telt, niet de volgorde.
    @Test
    void deStatusenumInHetGepubliceerdeDocumentDektElkeNotificatieStatus() {
        List<String> verwacht = Arrays.stream(NotificatieStatus.values())
                .map(NotificatieStatus::toApiValue).sorted().toList();

        assertEquals(verwacht, gepubliceerdeEnum("NotificatieStatus"));
    }

    @Test
    void deRedenenumInHetGepubliceerdeDocumentDektElkeReden() {
        List<String> verwacht = Arrays.stream(Reden.values()).map(Reden::toApiValue).sorted().toList();

        assertEquals(verwacht, gepubliceerdeEnum("Reden"));
    }

    private List<String> gepubliceerdeEnum(String schema) {
        List<Object> ruw = spec.getList("components.schemas." + schema + ".enum");
        assertNotNull(ruw, "het schema " + schema + " of zijn enum ontbreekt in /q/openapi");

        return ruw.stream().map(String::valueOf).sorted().toList();
    }
}
