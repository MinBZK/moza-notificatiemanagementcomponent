package nl.rijksoverheid.moz.nmc.fuzzing;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.api.ProfielApi;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.ContactgegevenResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.PartijResponse;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
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
 * both branches of the auth filter. Crash artifacts from ClusterFuzzLite carry
 * {@link NotificatieVerwerkingFuzzer}'s own input encoding, not this one, and belong in
 * `.clusterfuzzlite/seed-corpus/NotificatieVerwerkingFuzzer/`, where the next fuzz run replays
 * them. The test does not generate input itself: @QuarkusTest and jazzer both intercept the
 * test-template invocation, and jazzer's libFuzzer loop loses.
 *
 * <p>The Profielservice and NotifyNL clients are mocked: unreachable, they answer 500, which
 * would drown out the 5xx responses this test is looking for. The consument-callback client is
 * real; the seeded notificaties carry no callbackUrl, so the callback adapter returns without
 * network IO.
 */
@QuarkusTest
public class EndpointFuzzTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Matches %test.notify.callback.bearer-token in application.properties.
    private static final String CALLBACK_TOKEN = "test-callback-token-niet-voor-productie";

    // The external references the juiste-token seeds under EndpointFuzzTestInputs/fuzzAfleverstatus
    // carry. Persisted per invocation so those seeds reach verwerkAfleverstatus instead of
    // stopping at 404; without callbackUrl, so the (unmocked) callback adapter does no IO.
    private static final List<UUID> BEKENDE_NOTIFY_REFERENTIES = List.of(
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
            UUID.fromString("5a1f0c3e-2b4d-4e6f-8a9b-0c1d2e3f4a5b"));

    @InjectMock
    @RestClient
    ProfielApi profielApi;

    @InjectMock
    @RestClient
    SendAMessageApi sendAMessageApi;

    @Inject
    NotificatieRepository notificatieRepository;

    @BeforeEach
    void setUp() {
        // Wipes what earlier invocations committed, then seeds the notificaties the
        // afleverstatus-corpus refers to.
        QuarkusTransaction.requiringNew().run(() -> {
            notificatieRepository.deleteAll();
            for (UUID referentie : BEKENDE_NOTIFY_REFERENTIES) {
                Notificatie notificatie = new Notificatie(null);
                notificatie.setExternalReference(referentie);
                notificatieRepository.persist(notificatie);
            }
        });

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
