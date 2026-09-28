package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * Serialiseert het CloudEvent met de ObjectMapper van Quarkus, die ook de rest-client gebruikt, en
 * toetst de JSON aan NotificatieStatusEvent in het gepubliceerde /q/openapi.
 */
@QuarkusTest
class CloudEventContractTest {

    @Inject
    ObjectMapper objectMapper;

    @ParameterizedTest
    @EnumSource(StatusWaarde.class)
    void cloudEvent_alsJson_voldoetAanHetGepubliceerdeSchema(StatusWaarde status) throws Exception {
        JsonPath spec = given().queryParam("format", "JSON")
                .when().get("/q/openapi")
                .then().statusCode(200)
                .extract().jsonPath();
        JsonNode json = objectMapper.valueToTree(verstuurdEvent(status));

        bevestigVelden(json, spec.getMap("components.schemas.NotificatieStatusEvent"));
        bevestigVelden(json.get("data"), spec.getMap("components.schemas.NotificatieStatusData"));

        assertTrue(json.get("time").isTextual(), "time hoort een ISO-8601-string te zijn, geen getal");
        assertDoesNotThrow(() -> OffsetDateTime.parse(json.get("time").asText()));
        assertDoesNotThrow(() -> UUID.fromString(json.get("id").asText()));
        assertDoesNotThrow(() -> UUID.fromString(json.get("data").get("notificatieId").asText()));
        List<String> toegestaan = spec.getList("components.schemas.NotificatieStatus.enum", String.class);
        assertTrue(toegestaan.contains(json.get("data").get("status").asText()),
                "data.status hoort een waarde uit de NotificatieStatus-enum te zijn");
    }

    // Elk verplicht veld aanwezig en geen veld dat het schema niet kent.
    @SuppressWarnings("unchecked")
    private static void bevestigVelden(JsonNode json, Map<String, Object> schema) {
        Set<String> velden = new HashSet<>();
        json.fieldNames().forEachRemaining(velden::add);

        assertTrue(velden.containsAll((List<String>) schema.get("required")),
                "verplichte velden ontbreken: " + schema.get("required") + " vs " + velden);
        assertEquals(Set.of(), difference(velden, ((Map<String, Object>) schema.get("properties")).keySet()),
                "velden die het schema niet kent");
    }

    private static Set<String> difference(Set<String> links, Set<String> rechts) {
        Set<String> verschil = new HashSet<>(links);
        verschil.removeAll(rechts);

        return verschil;
    }

    private static NotificatieStatusEvent verstuurdEvent(StatusWaarde status) {
        ConsumentCallbackClient client = Mockito.mock(ConsumentCallbackClient.class);
        new ConsumentCallbackAdapter(url -> client, 0L)
                .stuurStatusUpdate(new StatusUpdateOpdracht(UUID.randomUUID(), "https://omc.example.nl/callback", status));

        ArgumentCaptor<NotificatieStatusEvent> captor = ArgumentCaptor.forClass(NotificatieStatusEvent.class);
        verify(client).stuurStatusUpdate(captor.capture());

        return captor.getValue();
    }
}
