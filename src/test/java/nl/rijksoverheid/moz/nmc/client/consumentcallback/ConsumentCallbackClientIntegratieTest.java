package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Reden;
import nl.rijksoverheid.moz.nmc.testhelper.WebhookOntvanger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URL;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * De echte rest-client tegen een echt HTTP-endpoint: welke antwoorden als mislukt tellen, en wat de
 * webhook van de Dienstverlener ontvangt.
 */
@QuarkusTest
class ConsumentCallbackClientIntegratieTest {

    @TestHTTPResource("/test/webhook")
    URL ontvanger;

    @Inject
    QuarkusConsumentCallbackClientFactory clientFactory;

    @Inject
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        WebhookOntvanger.AANROEPEN.clear();
    }

    @Test
    void antwoord2xx_teltAlsAfgeleverdMetHeaders() {
        try (ConsumentCallbackClient client = clientFactory.maakClient(ontvanger + "/204")) {
            assertDoesNotThrow(() -> client.lever("Bearer jwt", "cursor-1", List.of(event(UUID.randomUUID()))));
        }

        WebhookOntvanger.Aanroep aanroep = WebhookOntvanger.AANROEPEN.getFirst();
        assertEquals("Bearer jwt", aanroep.autorisatie());
        assertEquals("cursor-1", aanroep.cursor());
    }

    // Een redirect wordt niet gevolgd (dat zou de URL-validatie omzeilen) en mag dus ook niet stil als
    // afgeleverd tellen.
    @ParameterizedTest
    @ValueSource(ints = {301, 302, 307, 400, 500})
    void antwoordBuiten2xx_gooitWebApplicationException(int status) {
        try (ConsumentCallbackClient client = clientFactory.maakClient(ontvanger + "/" + status)) {
            WebApplicationException fout = assertThrows(WebApplicationException.class,
                    () -> client.lever("Bearer jwt", "cursor", List.of(event(UUID.randomUUID()))));

            assertEquals(status, fout.getResponse().getStatus());
        }

        assertEquals(1, WebhookOntvanger.AANROEPEN.size());
    }

    @Test
    void contentType_isWatDeGepubliceerdeSpecBelooft() throws Exception {
        try (ConsumentCallbackClient client = clientFactory.maakClient(ontvanger + "/204")) {
            client.lever("Bearer jwt", "cursor", List.of(event(UUID.randomUUID())));
        }

        JsonNode content = objectMapper.readTree(given().queryParam("format", "JSON")
                        .when().get("/q/openapi")
                        .then().statusCode(200)
                        .extract().asString())
                .path("paths").path("/api/nmc/v1/notificaties/wijzigingen").path("get").path("callbacks")
                .path("webhook").path("{webhookUrl}").path("post").path("requestBody").path("content");
        assertEquals(1, content.size(), "precies één mediatype verwacht voor de webhook");
        String verzonden = WebhookOntvanger.AANROEPEN.getFirst().contentType();
        assertEquals(content.fieldNames().next(), verzonden.split(";")[0].trim());
    }

    // Wat de rest-client echt over de lijn stuurt, niet wat een losse ObjectMapper ervan maakt.
    @Test
    void verzondenBody_isEenArrayVanCloudEventsUitDeSpec() throws Exception {
        UUID notificatieId = UUID.randomUUID();
        try (ConsumentCallbackClient client = clientFactory.maakClient(ontvanger + "/204")) {
            client.lever("Bearer jwt", "cursor", List.of(event(notificatieId), event(UUID.randomUUID())));
        }

        JsonNode body = objectMapper.readTree(WebhookOntvanger.AANROEPEN.getFirst().body());
        assertTrue(body.isArray());
        assertEquals(2, body.size());
        JsonNode eerste = body.get(0);
        assertEquals("1.0", eerste.path("specversion").asText());
        assertTrue(eerste.path("time").isTextual(), "time hoort een ISO-8601-string te zijn, geen getal");
        assertDoesNotThrow(() -> OffsetDateTime.parse(eerste.path("time").asText()));
        assertEquals(notificatieId.toString(), eerste.path("subject").asText());
        assertTrue(eerste.path("sequence").isTextual(), "sequence hoort een string te zijn");
        assertEquals("3", eerste.path("sequence").asText());
        assertEquals("verzonden", eerste.path("data").path("van").asText());
        assertEquals("technisch-mislukt", eerste.path("data").path("naar").asText());
        assertEquals("technisch", eerste.path("data").path("reden").asText());
    }

    // Een geweigerde verbinding is een transportfout: de levering is mislukt, niet het NMC.
    @Test
    void geweigerdeVerbinding_wordtEenLeveringException() {
        ConsumentCallbackAdapter adapter = new ConsumentCallbackAdapter(clientFactory);

        assertThrows(WebhookLeveringException.class,
                () -> adapter.lever("http://localhost:1/webhook", "Bearer jwt", "cursor", List.of(event(UUID.randomUUID()))));
    }

    private static NotificatieStatusEvent event(UUID id) {
        return NotificatieStatusEvent.van(7L, id, 3L, NotificatieStatus.VERZONDEN, NotificatieStatus.TECHNISCH_MISLUKT,
                Reden.TECHNISCH, OffsetDateTime.parse("2026-01-15T10:00:00Z"));
    }
}
