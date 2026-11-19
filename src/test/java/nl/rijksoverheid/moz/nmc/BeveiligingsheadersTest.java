package nl.rijksoverheid.moz.nmc;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;

/**
 * Bewaakt dat de beveiligingsheaders uit {@code application.properties} op de antwoorden van de
 * app-poort staan, ook op een pad buiten de eigen controllers en op een foutantwoord.
 */
@QuarkusTest
class BeveiligingsheadersTest {

    @ParameterizedTest
    @ValueSource(strings = {"/q/openapi", "/api/nmc/v1/bestaat-niet"})
    void antwoord_opElkPad_draagtDeBeveiligingsheaders(String pad) {
        given()
                .when().get(pad)
                .then()
                .header("X-Content-Type-Options", "nosniff")
                .header("Cross-Origin-Resource-Policy", "same-origin");
    }
}
