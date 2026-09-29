package nl.rijksoverheid.moz.nmc;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Reden;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Bewaakt dat de statuswaarden en redenen die een Dienstverlener in de feed en op de webhook kan krijgen
 * als echte enum in het gepubliceerde document staan, en wat er van de intakecontracten verdwenen is.
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

    // De statusupdate gaat naar de webhook uit het register; een callbackUrl per aanvraag bestaat niet meer.
    @Test
    void deIntakecontractenKennenGeenCallbackUrlMeer() {
        for (String schema : List.of("NotificatieAanvraagRequest", "DecentraleNotificatieAanvraagRequest")) {
            Map<String, Object> velden = spec.getMap("components.schemas." + schema + ".properties");
            assertNotNull(velden, "het schema " + schema + " ontbreekt in /q/openapi");
            assertFalse(velden.containsKey("callbackUrl"), schema + " hoort geen callbackUrl te hebben");
        }

        for (String pad : List.of("/api/nmc/v1/centraal/notificaties", "/api/nmc/v1/decentraal/notificaties")) {
            assertNull(spec.get("paths.'" + pad + "'.post.callbacks"), pad + " hoort geen callback te beschrijven");
        }

        assertNotNull(spec.get("paths.'/api/nmc/v1/notificaties/wijzigingen'.get.callbacks.webhook"));
        assertNotNull(spec.get("paths.'/api/nmc/v1/.well-known/jwks.json'.get"));
    }

    private List<String> gepubliceerdeEnum(String schema) {
        List<Object> ruw = spec.getList("components.schemas." + schema + ".enum");
        assertNotNull(ruw, "het schema " + schema + " of zijn enum ontbreekt in /q/openapi");

        return ruw.stream().map(String::valueOf).sorted().toList();
    }
}
