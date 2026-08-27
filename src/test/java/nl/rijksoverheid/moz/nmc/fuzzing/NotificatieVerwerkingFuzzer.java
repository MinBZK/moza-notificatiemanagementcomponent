package nl.rijksoverheid.moz.nmc.fuzzing;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkiverse.httpproblem.HttpProblem;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext;
import nl.rijksoverheid.moz.nmc.api.model.DecentraleNotificatieAanvraagRequest;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieAanvraagRequest;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.ConsumentCallbackAdapter;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.ConsumentCallbackClient;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLAuthorizationHolder;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLJwtFactory;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLVerzendAdapter;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.ProfielServiceAdapter;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.api.ProfielApi;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.ContactgegevenResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.PartijResponse;
import nl.rijksoverheid.moz.nmc.controller.CentraleNotificatieController;
import nl.rijksoverheid.moz.nmc.controller.DecentraleNotificatieController;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.helper.HashHelper;
import nl.rijksoverheid.moz.nmc.notifynlcallback.api.model.AfleverstatusRequest;
import nl.rijksoverheid.moz.nmc.notifynlcallback.controller.NotifyNLCallbackController;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.service.NotificatieService;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Standalone fuzz target for ClusterFuzzLite.
 * Drives caller-supplied JSON through the chain behind the three POST endpoints — Jackson, bean
 * validation, controller, service, NotifyNL-adapter — with in-memory stand-ins for the IO.
 *
 * <p>In the fuzzer's own JVM, because jazzer_driver instruments only what it loads itself: the
 * earlier variant fuzzed a separate Quarkus process, leaving libFuzzer without coverage to steer on.
 *
 * <p>A Jackson error, a constraint violation and an {@link HttpProblem} are expected outcomes.
 * Anything else is a finding, and so is a 5xx while every stand-in was told to succeed, or a
 * notificatie that survives a delivered callback or disappears after a failed one.
 */
public class NotificatieVerwerkingFuzzer {

    // NotifyNL key shape (naam-<serviceId>-<secret>); under 74 characters the factory rejects it.
    private static final String API_KEY =
            "niet-voor-productie-00000000-0000-0000-0000-000000000000-11111111-1111-1111-1111-111111111111";

    // Fixed instead of random: the same input has to do the same thing every run, or a crash
    // artifact will not replay.
    private static final UUID NOTIFY_REFERENTIE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PARTIJ_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final String[] IDENTIFICATIE_TYPES = {"BSN", "KVK", "RSIN", "INVALID"};
    private static final String[] BERICHT_TYPES = {"Stuurgroep Agenda", "Demo template", "onbekend"};
    private static final String[] AFLEVER_STATUSSEN = {
        "delivered", "permanent-failure", "temporary-failure", "technical-failure", "onbekend"
    };

    // Quarkus's mapper accepts unknown properties; rejecting them here would discard input the
    // real endpoints process, and libFuzzer mutations add keys all the time.
    private static final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final Validator validator = Validation.byDefaultProvider()
            .configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory()
            .getValidator();

    /** 0 = partij met e-mailadres, 1 = 404, 2 = 500. */
    private static int profielAntwoord;
    /** 0 = geaccepteerd, 1 = afgewezen, 2 = respons zonder notificatie-id. */
    private static int notifyAntwoord;
    private static boolean callbackLukt;

    private static final GeheugenNotificatieRepository repository = new GeheugenNotificatieRepository();

    private static final CentraleNotificatieController centraleController;
    private static final DecentraleNotificatieController decentraleController;
    private static final NotifyNLCallbackController callbackController;

    static {
        // Logging a stack trace per rejected input costs more time than the fuzzing itself.
        Logger.getLogger("").setLevel(Level.OFF);

        NotificatieService service = new NotificatieService(
                new ProfielServiceAdapter(profielApiStandIn()),
                new NotifyNLVerzendAdapter(notifyApiStandIn(), new NotifyNLJwtFactory(),
                        new NotifyNLAuthorizationHolder(), Optional.of(API_KEY)),
                repository,
                // Zero backoff: the adapter sleeps between callback retries.
                new ConsumentCallbackAdapter(url -> callbackClientStandIn(), 0));

        LogboekContext logboekContext = new LogboekContext();
        HashHelper hashHelper = new HashHelper(Optional.of("fuzz-pepper-niet-voor-productie"));

        centraleController = new CentraleNotificatieController(service, logboekContext, hashHelper);
        decentraleController = new DecentraleNotificatieController(service, logboekContext, hashHelper);
        callbackController = new NotifyNLCallbackController(service);
    }

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        repository.leegmaken();

        int route = data.consumeInt(0, 4);
        profielAntwoord = gewogenAntwoord(data);
        notifyAntwoord = gewogenAntwoord(data);
        callbackLukt = data.consumeBoolean();
        boolean notificatieIsBekend = data.consumeBoolean();

        switch (route) {
            case 0 -> centraal(centraleAanvraag(data));
            case 1 -> decentraal(decentraleAanvraag(data));
            case 2 -> afleverstatus(afleverstatusMelding(data), notificatieIsBekend);
            case 3 -> centraal(data.consumeRemainingAsString());
            case 4 -> decentraal(data.consumeRemainingAsString());
        }
    }

    private static void centraal(String json) {
        NotificatieAanvraagRequest aanvraag = lees(json, NotificatieAanvraagRequest.class);
        if (aanvraag != null) {
            roepAan(() -> centraleController.notificatieVersturen(aanvraag),
                    profielAntwoord == 0 && notifyAntwoord == 0);
        }
    }

    private static void decentraal(String json) {
        DecentraleNotificatieAanvraagRequest aanvraag = lees(json, DecentraleNotificatieAanvraagRequest.class);
        if (aanvraag != null) {
            roepAan(() -> decentraleController.decentraleNotificatieVersturen(aanvraag), notifyAntwoord == 0);
        }
    }

    private static void afleverstatus(String json, boolean notificatieIsBekend) {
        AfleverstatusRequest melding = lees(json, AfleverstatusRequest.class);
        if (melding == null) {
            return;
        }
        if (notificatieIsBekend) {
            repository.bewaarMetExterneReferentie(melding.getId());
        }
        // Unconditionally true: the adapter absorbs a failing callback (retries, then false), so
        // this route may never answer 5xx, whatever the callback stand-in does.
        roepAan(() -> callbackController.verwerkAfleverstatus(melding), true);

        // The service deletes the notificatie only after a delivered callback; a failed callback
        // keeps it for a retry.
        if (notificatieIsBekend) {
            boolean bewaard = repository.findByExternalReference(melding.getId()).isPresent();
            if (bewaard == callbackLukt) {
                throw new AssertionError("notificatie %s na %s consument-callback".formatted(
                        bewaard ? "bewaard" : "verwijderd", callbackLukt ? "geslaagde" : "mislukte"));
            }
        }
    }

    /**
     * Returns null for everything the HTTP layer answers with a 400 before the controller runs.
     * Bean validation lives in the JAX-RS layer, so skipping it here would report every empty
     * string and malformed e-mail address as a crash.
     */
    private static <T> T lees(String json, Class<T> type) {
        T request;
        try {
            request = mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            return null;
        }

        if (request == null || !validator.validate(request).isEmpty()) {
            return null;
        }
        return request;
    }

    /**
     * Weights success 4:1:1 over the three answers. The 5xx-oracle in {@link #roepAan} only fires
     * when every stand-in the route reaches succeeds; uniform answers would leave the centrale
     * route checked for 1 in 9 inputs.
     */
    private static int gewogenAntwoord(FuzzedDataProvider data) {
        int keuze = data.consumeInt(0, 5);
        return keuze <= 3 ? 0 : keuze - 3;
    }

    /**
     * @param geen5xxVerwacht whether a 5xx counts as a finding on this route. For the notificatie
     *                        routes: every stand-in the route reaches is set to succeed — only
     *                        those count, gating on a stand-in the route never calls would leave
     *                        most of its input unchecked.
     */
    private static void roepAan(Runnable aanroep, boolean geen5xxVerwacht) {
        try {
            aanroep.run();
        } catch (HttpProblem e) {
            if (e.getStatusCode() >= 500 && geen5xxVerwacht) {
                // The HttpProblem carries no cause (the controller only logs it), so the crash
                // artifact must name the stand-in states to be reconstructable.
                throw new AssertionError(
                        "5xx voor invoer die de validatie doorkwam (profielAntwoord=%d, notifyAntwoord=%d, callbackLukt=%b)"
                                .formatted(profielAntwoord, notifyAntwoord, callbackLukt), e);
            }
        }
    }

    private static String centraleAanvraag(FuzzedDataProvider data) {
        ObjectNode body = mapper.createObjectNode();
        body.put("identificatieType", data.pickValue(IDENTIFICATIE_TYPES));
        body.put("identificatieNummer", data.consumeString(20));
        body.put("dienstverlener", data.consumeString(50));
        body.put("dienst", data.consumeString(50));
        body.put("berichtType", data.pickValue(BERICHT_TYPES));
        body.putObject("berichtgegevens").put(data.consumeString(20), data.consumeString(50));
        body.put("callbackUrl", callbackUrl(data));
        return body.toString();
    }

    private static String decentraleAanvraag(FuzzedDataProvider data) {
        ObjectNode body = mapper.createObjectNode();
        // Half the inputs pass the pattern, so the send path stays reachable.
        body.put("emailAdres", data.consumeBoolean() ? "fuzz@example.invalid" : data.consumeString(60));
        body.put("berichtType", data.pickValue(BERICHT_TYPES));
        body.putObject("berichtgegevens").put(data.consumeString(20), data.consumeString(50));
        body.put("callbackUrl", callbackUrl(data));
        return body.toString();
    }

    private static String afleverstatusMelding(FuzzedDataProvider data) {
        ObjectNode body = mapper.createObjectNode();
        // id is a UUID and created_at an OffsetDateTime: a fuzzed string stops at the parser.
        body.put("id", data.consumeBoolean()
                ? new UUID(data.consumeLong(), data.consumeLong()).toString()
                : data.consumeString(40));
        body.put("reference", data.consumeString(40));
        body.put("to", data.consumeString(60));
        body.put("status", data.pickValue(AFLEVER_STATUSSEN));
        body.put("notification_type", data.consumeString(20));
        body.put("created_at", data.consumeBoolean() ? "2026-01-01T00:00:00Z" : data.consumeString(30));
        return body.toString();
    }

    /**
     * A closed local port on purpose: the application POSTs to this URL later, so arbitrary hosts
     * would turn the fuzzer into an outbound request generator.
     */
    private static String callbackUrl(FuzzedDataProvider data) {
        return "http://localhost:9999/" + data.consumeString(20);
    }

    private static ProfielApi profielApiStandIn() {
        return standIn(ProfielApi.class, "apiProfielserviceV1PartijPost", () -> switch (profielAntwoord) {
            case 0 -> partijMetEmailadres();
            case 1 -> throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build());
            default -> throw new WebApplicationException(Response.status(Response.Status.INTERNAL_SERVER_ERROR).build());
        });
    }

    private static SendAMessageApi notifyApiStandIn() {
        return standIn(SendAMessageApi.class, "sendEmail", () -> switch (notifyAntwoord) {
            case 0 -> new SendEmailResponse().id(NOTIFY_REFERENTIE.toString());
            case 1 -> throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).build());
            default -> new SendEmailResponse();
        });
    }

    private static ConsumentCallbackClient callbackClientStandIn() {
        return event -> {
            if (!callbackLukt) {
                throw new WebApplicationException(Response.status(Response.Status.BAD_GATEWAY).build());
            }
        };
    }

    /** The generated clients carry a method per operation; the NMC calls exactly one of them. */
    private static <T> T standIn(Class<T> api, String methode, Supplier<Object> antwoord) {
        return api.cast(Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        // Without this hashCode() answers null and unboxing it throws.
                        return switch (method.getName()) {
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> api.getSimpleName() + "-stand-in";
                        };
                    }
                    if (!methode.equals(method.getName())) {
                        // Loud instead of null: a renamed generated operation would otherwise NPE
                        // in the adapter and read as a product bug on every input.
                        throw new UnsupportedOperationException(
                                api.getSimpleName() + "-stand-in kent " + method.getName() + " niet");
                    }
                    return antwoord.get();
                }));
    }

    private static PartijResponse partijMetEmailadres() {
        return new PartijResponse()
                .partijId(PARTIJ_ID)
                .contactgegevens(List.of(new ContactgegevenResponse()
                        .type(ContactgegevenResponse.TypeEnum.EMAIL)
                        .waarde("fuzz@example.invalid")
                        .isDefault(true)));
    }

    /**
     * Stand-in for the Panache repository. Column constraints (such as the 2048 characters the
     * schema gives a callback URL) are not enforced here.
     */
    private static final class GeheugenNotificatieRepository extends NotificatieRepository {

        private static final Field ID_VELD = idVeld();

        private final Map<UUID, Notificatie> opgeslagen = new HashMap<>();

        @Override
        public void persist(Notificatie notificatie) {
            zetId(notificatie, new UUID(0, opgeslagen.size() + 1L));
            opgeslagen.put(notificatie.getId(), notificatie);
        }

        /** Empty per input, otherwise found-or-not depends on what earlier inputs left behind. */
        void leegmaken() {
            opgeslagen.clear();
        }

        @Override
        public void flush() {
            // Nothing is written, so there is nothing to flush.
        }

        @Override
        public boolean deleteById(UUID id) {
            return opgeslagen.remove(id) != null;
        }

        @Override
        public Optional<Notificatie> findByExternalReference(UUID externalReference) {
            return opgeslagen.values().stream()
                    .filter(n -> externalReference.equals(n.getExternalReference()))
                    .findFirst();
        }

        /** Lets the callback route reach a notificatie instead of stopping at "niet gevonden". */
        void bewaarMetExterneReferentie(UUID externalReference) {
            Notificatie notificatie = new Notificatie("http://localhost:9999/callback");
            notificatie.setExternalReference(externalReference);
            persist(notificatie);
        }

        private static void zetId(Notificatie notificatie, UUID id) {
            try {
                ID_VELD.set(notificatie, id);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        private static Field idVeld() {
            try {
                // JPA assigns the generated id on persist; the entity has no setter for it.
                Field veld = Notificatie.class.getDeclaredField("id");
                veld.setAccessible(true);
                return veld;
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
