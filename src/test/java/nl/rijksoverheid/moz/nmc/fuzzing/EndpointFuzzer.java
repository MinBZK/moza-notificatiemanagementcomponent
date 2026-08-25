package nl.rijksoverheid.moz.nmc.fuzzing;

import com.code_intelligence.jazzer.api.BugDetectors;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Standalone fuzz target for ClusterFuzzLite.
 * Expects Quarkus to be running as a separate process (started by the wrapper
 * script) with an H2 in-memory database on port 8081. This fuzzer sends
 * coverage-guided HTTP requests to the three POST endpoints the NMC exposes.
 */
public class EndpointFuzzer {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final HttpClient client;
    private static final String BASE = "http://localhost:8081/api/nmc/v1";

    // Must match notify.callback.bearer-token as passed by the wrapper script.
    private static final String CALLBACK_TOKEN = "fuzz-callback-token";

    private static final String[] IDENTIFICATIE_TYPES = {"BSN", "KVK", "RSIN", "INVALID"};
    private static final String[] BERICHT_TYPES = {"Stuurgroep Agenda", "Demo template", "onbekend"};
    private static final String[] AFLEVER_STATUSSEN = {
        "delivered", "permanent-failure", "temporary-failure", "technical-failure", "onbekend"
    };

    // Keep reference to prevent GC; allows network connections for the main thread.
    @SuppressWarnings("unused")
    private static final AutoCloseable networkAllowed;

    static {
        // Must be called before any HTTP connection to avoid Jazzer's SSRF sanitizer
        networkAllowed = BugDetectors.allowNetworkConnections();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        int endpoint = data.consumeInt(0, 5);
        try {
            switch (endpoint) {
                case 0 -> fuzzCentraleNotificatie(data);
                case 1 -> fuzzDecentraleNotificatie(data);
                case 2 -> fuzzAfleverstatus(data, true);
                case 3 -> fuzzAfleverstatus(data, false);
                case 4 -> postRaw("/centraal/notificaties", data.consumeRemainingAsString(), null);
                case 5 -> postRaw("/decentraal/notificaties", data.consumeRemainingAsString(), null);
            }
        } catch (Exception e) {
            // Expected for invalid inputs or connection issues
        }
    }

    private static void postRaw(String path, String body, String bearerToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        client.send(request.build(), HttpResponse.BodyHandlers.discarding());
    }

    /**
     * Callback URLs are kept on a closed local port on purpose. The field is
     * unvalidated caller input that the application POSTs to later, so feeding it
     * arbitrary hosts would turn the fuzzer into an outbound request generator.
     * A dead local port still exercises the same code path.
     */
    private static String fuzzedCallbackUrl(FuzzedDataProvider data) {
        return "http://localhost:9999/" + data.consumeString(20);
    }

    private static void fuzzCentraleNotificatie(FuzzedDataProvider data) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("identificatieType", data.pickValue(IDENTIFICATIE_TYPES));
        body.put("identificatieNummer", data.consumeString(20));
        body.put("dienstverlener", data.consumeString(50));
        body.put("dienst", data.consumeString(50));
        body.put("berichtType", data.pickValue(BERICHT_TYPES));
        body.putObject("berichtgegevens").put(data.consumeString(20), data.consumeString(50));
        body.put("callbackUrl", fuzzedCallbackUrl(data));

        postRaw("/centraal/notificaties", body.toString(), null);
    }

    private static void fuzzDecentraleNotificatie(FuzzedDataProvider data) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("emailAdres", data.consumeString(60));
        body.put("berichtType", data.pickValue(BERICHT_TYPES));
        body.putObject("berichtgegevens").put(data.consumeString(20), data.consumeString(50));
        body.put("callbackUrl", fuzzedCallbackUrl(data));

        postRaw("/decentraal/notificaties", body.toString(), null);
    }

    /**
     * @param authenticated true sends the token the auth filter expects, false sends a
     *                      fuzzed one — so both the accepted and the rejected path get
     *                      exercised.
     */
    private static void fuzzAfleverstatus(FuzzedDataProvider data, boolean authenticated) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("id", data.consumeString(40));
        body.put("reference", data.consumeString(40));
        body.put("to", data.consumeString(60));
        body.put("status", data.pickValue(AFLEVER_STATUSSEN));
        body.put("notification_type", data.consumeString(20));
        body.put("created_at", data.consumeString(30));

        String token = authenticated ? CALLBACK_TOKEN : data.consumeString(40);
        postRaw("/notifynl-callback", body.toString(), token);
    }
}
