package nl.rijksoverheid.moz.nmc.controller;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;

@QuarkusTest
class SleutelsControllerTest {

    @Test
    void jwks_publiceertAlleenDePubliekeRsaSleutel() {
        given()
                .when().get("/api/nmc/v1/.well-known/jwks.json")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("keys", hasSize(1))
                .body("keys[0].kty", equalTo("RSA"))
                .body("keys[0].use", equalTo("sig"))
                .body("keys[0].alg", equalTo("RS256"))
                .body("keys[0].kid", equalTo("test-webhook-sleutel-niet-voor-productie"))
                .body("keys[0].e", equalTo("AQAB"))
                .body("keys[0].n", not(emptyString()))
                .body("keys[0].d", equalTo(null));
    }
}
