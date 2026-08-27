package nl.rijksoverheid.moz.nmc.fuzzing;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.api.ProfielApi;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.ContactgegevenResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.PartijResponse;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.lessThan;
import static org.mockito.ArgumentMatchers.any;

/**
 * In-process counterpart of {@link NotificatieVerwerkingFuzzer}: the same request handling over
 * HTTP through a @QuarkusTest, so the JAX-RS layer (bean validation, the callback auth filter) is
 * covered too. In a normal `mvn verify` each method replays the seed corpus under
 * `src/test/resources/.../EndpointFuzzTestInputs/&lt;methode&gt;/` — happy paths, rejected input,
 * both branches of the auth filter. Crash artifacts from ClusterFuzzLite runs belong there too, as
 * regression inputs. The test does not generate input itself: @QuarkusTest and jazzer both
 * intercept the test-template invocation, and jazzer's libFuzzer loop loses.
 *
 * <p>Both outbound clients are mocked: an unreachable Profielservice or NotifyNL answers 500,
 * which would drown out the 5xx responses this test is looking for.
 */
@QuarkusTest
public class EndpointFuzzTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Matches %test.notify.callback.bearer-token in application.properties.
    private static final String CALLBACK_TOKEN = "test-callback-token-niet-voor-productie";

    @InjectMock
    @RestClient
    ProfielApi profielApi;

    @InjectMock
    @RestClient
    SendAMessageApi sendAMessageApi;

    @BeforeEach
    void setUp() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any())).thenReturn(partijMetEmailadres());
        // One id per invocation: external_reference is unique and each fuzz method sends exactly once.
        Mockito.when(sendAMessageApi.sendEmail(any()))
                .thenReturn(new SendEmailResponse().id(UUID.randomUUID().toString()));
    }

    @FuzzTest
    public void fuzzCentraleNotificatie(FuzzedDataProvider data) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("identificatieType", data.pickValue(new String[]{"BSN", "KVK", "RSIN", "INVALID"}));
        body.put("identificatieNummer", data.consumeString(20));
        body.put("dienstverlener", data.consumeString(50));
        body.put("dienst", data.consumeString(50));
        body.put("berichtType", data.pickValue(new String[]{"Stuurgroep Agenda", "Demo template", "onbekend"}));
        body.put("callbackUrl", "http://localhost:9999/" + data.consumeString(20));

        post("/api/nmc/v1/centraal/notificaties", body.toString(), null);
    }

    @FuzzTest
    public void fuzzDecentraleNotificatie(FuzzedDataProvider data) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("emailAdres", data.consumeString(60));
        body.put("berichtType", data.pickValue(new String[]{"Stuurgroep Agenda", "Demo template", "onbekend"}));
        body.put("callbackUrl", "http://localhost:9999/" + data.consumeString(20));

        post("/api/nmc/v1/decentraal/notificaties", body.toString(), null);
    }

    @FuzzTest
    public void fuzzAfleverstatus(FuzzedDataProvider data) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("id", data.consumeString(40));
        body.put("reference", data.consumeString(40));
        body.put("to", data.consumeString(60));
        body.put("status", data.pickValue(new String[]{
                "delivered", "permanent-failure", "temporary-failure", "technical-failure", "onbekend"}));
        body.put("notification_type", data.consumeString(20));
        body.put("created_at", data.consumeString(30));

        // Half the inputs use the expected token, half a fuzzed one, so both the
        // accepted and the rejected path through the auth filter are covered.
        String token = data.consumeBoolean() ? CALLBACK_TOKEN : data.consumeString(40);

        post("/api/nmc/v1/notifynl-callback", body.toString(), token);
    }

    @FuzzTest
    public void fuzzRawJsonBody(FuzzedDataProvider data) {
        String path = data.consumeBoolean()
                ? "/api/nmc/v1/centraal/notificaties"
                : "/api/nmc/v1/decentraal/notificaties";

        post(path, data.consumeRemainingAsString(), null);
    }

    /**
     * Any 4xx is fine, the endpoints are supposed to reject nonsense. A 5xx is not: with both
     * clients mocked, only the NMC itself is left to trip over the caller input.
     */
    private void post(String path, String body, String bearerToken) {
        var request = RestAssured.given().contentType(ContentType.JSON).body(body);
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }

        request.when().post(path).then().statusCode(lessThan(500));
    }

    private PartijResponse partijMetEmailadres() {
        return new PartijResponse()
                .partijId(UUID.randomUUID())
                .contactgegevens(List.of(new ContactgegevenResponse()
                        .type(ContactgegevenResponse.TypeEnum.EMAIL)
                        .waarde("fuzz@example.invalid")
                        .isDefault(true)));
    }
}
