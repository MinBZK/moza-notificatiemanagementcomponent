package nl.rijksoverheid.moz.nmc.fuzzing;

import static org.hamcrest.Matchers.lessThan;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.api.ProfielApi;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TestDatabase;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

/**
 * HTTP counterpart of {@link NotificatieVerwerkingFuzzer} through a @QuarkusTest, which adds the
 * JAX-RS layer: bean validation and the callback auth filter. Each method replays the seed corpus
 * under `src/test/resources/.../EndpointFuzzTestInputs/&lt;methode&gt;/`: happy paths, rejected
 * input, both branches of the auth filter. No input is generated here; under @QuarkusTest the
 * test-template interceptor skips jazzer's fuzzing loop. Crash artifacts from ClusterFuzzLite use
 * {@link NotificatieVerwerkingFuzzer}'s input encoding and belong in
 * `.clusterfuzzlite/seed-corpus/NotificatieVerwerkingFuzzer/`.
 *
 * <p>The Profielservice and NotifyNL clients are mocked, so no test can reach an external service;
 * each test verifies they were not called.
 */
@QuarkusTest
public class EndpointFuzzTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Matches %test.notify.callback.bearer-token in application.properties.
    private static final String CALLBACK_TOKEN = "test-callback-token-niet-voor-productie";

    // External references used by the juiste-token seeds under
    // EndpointFuzzTestInputs/fuzzAfleverstatus.
    private static final List<UUID> BEKENDE_NOTIFY_REFERENTIES = List.of(
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
            UUID.fromString("5a1f0c3e-2b4d-4e6f-8a9b-0c1d2e3f4a5b"));

    @InjectMock
    @RestClient
    ProfielApi profielApi;

    @InjectMock
    @RestClient
    SendAMessageApi sendAMessageApi;

    @BeforeEach
    void setUp() {
        // Wipes what earlier invocations committed, then seeds the notificaties the
        // afleverstatus-corpus refers to.
        TestDatabase.leeg();

        for (UUID referentie : BEKENDE_NOTIFY_REFERENTIES) {
            NotificatieFixtures.verzondenNotificatie(referentie, OffsetDateTime.now(ZoneOffset.UTC));
        }
    }

    // The intake only stores the notificatie and the worker loop is off in tests, so nothing here
    // looks up an address or sends.
    @AfterEach
    void geenExterneAanroep() {
        Mockito.verifyNoInteractions(profielApi, sendAMessageApi);
    }

    @FuzzTest
    public void fuzzCentraleNotificatie(FuzzedDataProvider data) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("identificatieType", data.pickValue(new String[] {"BSN", "KVK", "RSIN", "INVALID"}));
        body.put("identificatieNummer", data.consumeString(20));
        body.put("dienstverlener", data.consumeString(50));
        body.put("dienst", data.consumeString(50));
        body.put("berichtType", data.pickValue(new String[] {"Stuurgroep Agenda", "Demo template", "onbekend"}));

        post("/api/nmc/v1/centraal/notificaties", body.toString(), null);
    }

    @FuzzTest
    public void fuzzDecentraleNotificatie(FuzzedDataProvider data) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("emailAdres", data.consumeString(60));
        body.put("berichtType", data.pickValue(new String[] {"Stuurgroep Agenda", "Demo template", "onbekend"}));

        post("/api/nmc/v1/decentraal/notificaties", body.toString(), null);
    }

    @FuzzTest
    public void fuzzAfleverstatus(FuzzedDataProvider data) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("id", data.consumeString(40));
        body.put("reference", data.consumeString(40));
        body.put("to", data.consumeString(60));
        body.put("status", data.pickValue(new String[] {
            "delivered", "permanent-failure", "temporary-failure", "technical-failure", "onbekend"
        }));
        body.put("notification_type", data.consumeString(20));
        body.put("created_at", data.consumeString(30));

        // Half the inputs use the expected token, half a fuzzed one, so both the
        // accepted and the rejected path through the auth filter are covered.
        String token = data.consumeBoolean() ? CALLBACK_TOKEN : data.consumeString(40);

        post("/api/nmc/v1/notifynl-callback", body.toString(), token);
    }

    @FuzzTest
    public void fuzzRawJsonBody(FuzzedDataProvider data) {
        String path =
                data.consumeBoolean() ? "/api/nmc/v1/centraal/notificaties" : "/api/nmc/v1/decentraal/notificaties";

        post(path, data.consumeRemainingAsString(), null);
    }

    /** Posts the body and asserts a status below 500. */
    private void post(String path, String body, String bearerToken) {
        var request = RestAssured.given().contentType(ContentType.JSON).body(body);
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }

        request.when().post(path).then().statusCode(lessThan(500));
    }
}
