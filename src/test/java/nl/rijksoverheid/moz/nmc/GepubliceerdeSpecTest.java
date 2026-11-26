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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void notificatieStatusEnum_inGepubliceerdeSpec_dektElkeNotificatieStatus() {
        List<String> verwacht = Arrays.stream(NotificatieStatus.values())
                .map(NotificatieStatus::toApiValue).sorted().toList();

        assertEquals(verwacht, gepubliceerdeEnum("NotificatieStatus"));
    }

    @Test
    void redenEnum_inGepubliceerdeSpec_dektElkeReden() {
        List<String> verwacht = Arrays.stream(Reden.values()).map(Reden::toApiValue).sorted().toList();

        assertEquals(verwacht, gepubliceerdeEnum("Reden"));
    }

    // Het contactherstelcontract is een eigen bestand; zonder de samenvoeging ontbreekt het in Swagger UI.
    @Test
    void contactherstel_inGepubliceerdeSpec_staatNaastDeNotificaties() {
        Map<String, Object> paden = spec.getMap("paths");

        assertTrue(paden.containsKey("/api/nmc/v1/contactherstellen"), "POST contactherstellen ontbreekt");
        assertTrue(paden.containsKey("/api/nmc/v1/contactherstellen/{contactherstelId}"),
                "GET en PATCH contactherstellen/{contactherstelId} ontbreken");
        assertTrue(paden.containsKey("/api/nmc/v1/centraal/notificaties"), "de notificaties zijn verdrongen");
        assertEquals(List.of("gestart", "afgeleverd", "afgerond", "ingetrokken", "gefaald"),
                spec.getList("components.schemas.ContactherstelStatus.enum"));
        assertEquals("NotificatieManagementComponent API", spec.getString("info.title"));
    }

    // Elk contract in META-INF wordt samengevoegd; het callbackcontract staat daarom in een andere map.
    @Test
    void notifynlCallback_staatNietInGepubliceerdeSpec() {
        Map<String, Object> paden = spec.getMap("paths");

        assertFalse(paden.containsKey("/api/nmc/v1/notifynl-callback"), "de NotifyNL-callback is gepubliceerd");
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

        assertNotNull(spec.get("paths.'/api/nmc/v1/notificaties/events'.get.callbacks.webhook"));
        assertNotNull(spec.get("paths.'/api/nmc/v1/.well-known/jwks.json'.get"));
    }

    // Het filter eist het token op elke controller; het contract moet dat per operatie zeggen.
    @Test
    void elkeOperatieVanEenDienstverlener_vraagtHetFscToegangstoken() {
        Map<String, Map<String, Map<String, Object>>> paden = spec.getMap("paths");
        int operaties = 0;

        for (Map.Entry<String, Map<String, Map<String, Object>>> pad : paden.entrySet()) {
            for (Map.Entry<String, Map<String, Object>> operatie : pad.getValue().entrySet()) {
                if (!HTTP_METHODES.contains(operatie.getKey())) {
                    continue;
                }

                String naam = operatie.getKey() + " " + pad.getKey();
                Object security = operatie.getValue().get("security");

                if (pad.getKey().equals("/api/nmc/v1/.well-known/jwks.json")) {
                    assertNull(security, naam + " is openbaar");
                    continue;
                }

                assertEquals(List.of(Map.of("fscToegangstoken", List.of())), security, naam);
                Map<?, ?> antwoorden = (Map<?, ?>) operatie.getValue().get("responses");
                assertTrue(antwoorden.keySet().containsAll(List.of("401", "403", "503")), naam + ": " + antwoorden.keySet());
                operaties++;
            }
        }

        assertEquals(7, operaties, "elke operatie van het notificatie- en het contactherstelcontract");
        assertEquals("Fsc-Authorization", spec.getString("components.securitySchemes.fscToegangstoken.name"));
    }

    private static final List<String> HTTP_METHODES = List.of("get", "post", "put", "patch", "delete");

    private List<String> gepubliceerdeEnum(String schema) {
        List<Object> ruw = spec.getList("components.schemas." + schema + ".enum");
        assertNotNull(ruw, "het schema " + schema + " of zijn enum ontbreekt in /q/openapi");

        return ruw.stream().map(String::valueOf).sorted().toList();
    }
}
