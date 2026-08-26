package nl.rijksoverheid.moz.nmc.fuzzing;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;

/**
 * In-process counterpart of {@link EndpointFuzzer}: the same endpoints, but driven
 * through a @QuarkusTest so they also run in a normal `mvn verify`. Without the
 * JAZZER_FUZZ environment variable set, jazzer replays the stored corpus instead of
 * generating new input, which makes this a regression test for anything the
 * ClusterFuzzLite runs found earlier.
 */
@QuarkusTest
public class EndpointFuzzTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Matches %test.notify.callback.bearer-token in application.properties.
    private static final String CALLBACK_TOKEN = "test-callback-token-niet-voor-productie";

    @FuzzTest
    public void fuzzCentraleNotificatie(FuzzedDataProvider data) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("identificatieType", data.pickValue(new String[]{"BSN", "KVK", "RSIN", "INVALID"}));
        body.put("identificatieNummer", data.consumeString(20));
        body.put("dienstverlener", data.consumeString(50));
        body.put("dienst", data.consumeString(50));
        body.put("berichtType", data.pickValue(new String[]{"Stuurgroep Agenda", "Demo template", "onbekend"}));
        body.put("callbackUrl", fuzzedCallbackUrl(data));

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body.toString())
                .when()
                .post("/api/nmc/v1/centraal/notificaties")
                .then()
                .extract().response();

        // No assertion on the status code: any response is fine, a crash is not.
    }

    @FuzzTest
    public void fuzzDecentraleNotificatie(FuzzedDataProvider data) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("emailAdres", data.consumeString(60));
        body.put("berichtType", data.pickValue(new String[]{"Stuurgroep Agenda", "Demo template", "onbekend"}));
        body.put("callbackUrl", fuzzedCallbackUrl(data));

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body.toString())
                .when()
                .post("/api/nmc/v1/decentraal/notificaties")
                .then()
                .extract().response();
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

        RestAssured.given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(body.toString())
                .when()
                .post("/api/nmc/v1/notifynl-callback")
                .then()
                .extract().response();
    }

    // Each entry trips a different CallbackUrlValidator reject branch. A raw fuzzed string
    // almost always dies at URI parsing or the first scheme check, so it never reaches the
    // host/userinfo/IP branches; a fixed pool of near-valid shapes does.
    private static final String[] ONGELDIGE_CALLBACK_URLS = {
            "http://consument.example.invalid/cb",          // scheme
            "https://user:pw@consument.example.invalid/cb", // userinfo
            "https://127.0.0.1/cb",                         // IPv4 literal
            "https://[::1]/cb",                             // IPv6 literal
            "https://intranet/cb",                          // single-label internal name
            "https://svc.ns.svc/cb",                        // internal suffix
            "geen-url",                                     // unparseable
    };

    // Half the URLs pass validation (reserved .invalid TLD, never resolves) so the send path
    // stays reachable; half exercise the reject path.
    private static String fuzzedCallbackUrl(FuzzedDataProvider data) {
        return data.consumeBoolean()
                ? "https://consument.example.invalid/" + data.consumeString(20)
                : data.pickValue(ONGELDIGE_CALLBACK_URLS);
    }

    @FuzzTest
    public void fuzzRawJsonBody(FuzzedDataProvider data) {
        String path = data.consumeBoolean()
                ? "/api/nmc/v1/centraal/notificaties"
                : "/api/nmc/v1/decentraal/notificaties";

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(data.consumeRemainingAsString())
                .when()
                .post(path)
                .then()
                .extract().response();
    }
}
