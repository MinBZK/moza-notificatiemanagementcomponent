package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URL;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * De echte rest-client tegen een echt HTTP-endpoint: welke antwoorden als mislukt tellen, en welk
 * Content-Type de Dienstverlener ontvangt.
 */
@QuarkusTest
class ConsumentCallbackClientIntegratieTest {

    @TestHTTPResource("/test/consument-callback")
    URL ontvanger;

    @Inject
    QuarkusConsumentCallbackClientFactory clientFactory;

    @Inject
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        ConsumentCallbackTestEndpoint.ONTVANGEN_CONTENT_TYPES.clear();
    }

    @Test
    void antwoord2xx_teltAlsAfgeleverd() {
        assertDoesNotThrow(() -> clientFactory.maakClient(ontvanger + "/204").stuurStatusUpdate(event()));
    }

    // Een redirect wordt niet gevolgd (dat zou CallbackUrlValidator omzeilen) en mag dus ook niet
    // stil als afgeleverd tellen.
    @ParameterizedTest
    @ValueSource(ints = {301, 302, 307, 400, 500})
    void antwoordBuiten2xx_gooitWebApplicationException(int status) {
        ConsumentCallbackClient client = clientFactory.maakClient(ontvanger + "/" + status);

        WebApplicationException fout = assertThrows(WebApplicationException.class,
                () -> client.stuurStatusUpdate(event()));

        assertEquals(status, fout.getResponse().getStatus());
    }

    @Test
    void contentType_isWatDeGepubliceerdeSpecBelooft() throws Exception {
        clientFactory.maakClient(ontvanger + "/204").stuurStatusUpdate(event());

        String verzonden = ConsumentCallbackTestEndpoint.ONTVANGEN_CONTENT_TYPES.getFirst();
        for (String beloofd : beloofdeContentTypes()) {
            assertEquals(beloofd, verzonden.split(";")[0].trim());
        }
    }

    // Een geweigerde verbinding is een transportfout: de adapter herhaalt, gooit niet en geeft op.
    @Test
    void geweigerdeVerbinding_wordtAlsTransportfoutAfgehandeld() {
        ConsumentCallbackAdapter adapter = new ConsumentCallbackAdapter(clientFactory, 0L);

        assertDoesNotThrow(() -> adapter.stuurStatusUpdate(new StatusUpdateOpdracht(
                UUID.randomUUID(), "http://localhost:1/callback", StatusWaarde.DELIVERED)));
    }

    private List<String> beloofdeContentTypes() throws Exception {
        JsonNode spec = objectMapper.readTree(given().queryParam("format", "JSON")
                .when().get("/q/openapi")
                .then().statusCode(200)
                .extract().asString());
        List<String> typen = new ArrayList<>();
        for (String pad : List.of("/api/nmc/v1/centraal/notificaties", "/api/nmc/v1/decentraal/notificaties")) {
            JsonNode content = spec.path("paths").path(pad).path("post").path("callbacks").path("statusupdate")
                    .path("{$request.body#/callbackUrl}").path("post").path("requestBody").path("content");
            assertEquals(1, content.size(), "precies één mediatype verwacht voor de callback op " + pad);
            typen.add(content.fieldNames().next());
        }

        return typen;
    }

    private static NotificatieStatusEvent event() {
        UUID id = UUID.randomUUID();

        return new NotificatieStatusEvent("1.0", UUID.randomUUID(), "nl.rijksoverheid.moz.nmc.notificatie.status",
                "/api/nmc/v1/notificaties/" + id, "notificatie/" + id, OffsetDateTime.now(ZoneOffset.UTC),
                "application/json", NotificatieData.van(id, StatusWaarde.DELIVERED));
    }
}
