package nl.rijksoverheid.moz.nmc;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bewaakt dat de statuswaarden die een Dienstverlener in de callback kan krijgen als echte enum in
 * het gepubliceerde document staan.
 * <p>
 * Toetst tegen {@code /q/openapi} en niet tegen {@code META-INF/openapi.yaml}: wat in de bron staat
 * zegt niets over wat een afnemer krijgt. En op de <em>enum</em> en niet op losse woorden, want de
 * statuswaarden staan ook in de beschrijving van {@code callbackUrl} — een tekstzoektocht blijft daar
 * groen terwijl het schema verdwenen is.
 */
@QuarkusTest
class GepubliceerdeSpecTest {

    @Test
    void deStatusenumInHetGepubliceerdeDocumentDektElkeStatusWaarde() {
        JsonPath spec = given()
                .queryParam("format", "JSON")
                .when().get("/q/openapi")
                .then().statusCode(200)
                .extract().jsonPath();

        List<Object> ruw = spec.getList("components.schemas.NotificatieStatus.enum");
        assertNotNull(ruw, "het NotificatieStatus-schema of zijn enum ontbreekt in /q/openapi");
        List<String> gepubliceerd = ruw.stream().map(String::valueOf).sorted().toList();
        // Afgeleid uit de enum zelf: een achtste StatusWaarde moet de spec laten vallen in plaats
        // van er stilzwijgend buiten te blijven. Gesorteerd vergeleken, want de spec ordent de
        // waarden naar levensloop en de Java-enum naar declaratie; alleen het lidmaatschap telt.
        List<String> verwacht = Arrays.stream(StatusWaarde.values())
                .map(StatusWaarde::toApiValue).sorted().toList();

        assertEquals(verwacht, gepubliceerd,
                "de statusenum in /q/openapi hoort exact StatusWaarde#toApiValue te dekken");
    }

    @Test
    void deToelichtingBijOnbekendStaatInHetGepubliceerdeDocument() {
        String beschrijving = given()
                .queryParam("format", "JSON")
                .when().get("/q/openapi")
                .then().statusCode(200)
                .extract().jsonPath()
                .getString("components.schemas.NotificatieStatus.description");

        assertTrue(beschrijving.contains("onbekend"), "de beschrijving hoort onbekend te benoemen");
        assertTrue(beschrijving.replaceAll("\\s+", " ").contains("door een andere uitkomst gevolgd"),
                "de beschrijving hoort te zeggen dat na onbekend nog een andere uitkomst kan volgen");
    }
}
