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
 * Toetst tegen {@code /q/openapi} en niet tegen {@code META-INF/openapi.yaml}: dat de waarden in het
 * bronbestand staan zegt niets over wat een afnemer krijgt. SmallRye bouwt het gepubliceerde
 * document op uit die bron, en een filter of een herschikking daar kan onderdelen laten vervallen
 * zonder dat het bestand verandert.
 * <p>
 * Toetst bovendien op de <em>enum</em> en niet op losse woorden in het document. De statuswaarden
 * staan namelijk óók in de beschrijving van {@code callbackUrl}; een test die op tekst zoekt zou
 * daar treffers vinden en groen blijven terwijl het schema was verdwenen — precies wat hij hoort te
 * betrappen.
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
        assertTrue(beschrijving.contains("niet als eindstatus"),
                "de beschrijving hoort te zeggen dat onbekend geen eindstatus is");
    }
}
