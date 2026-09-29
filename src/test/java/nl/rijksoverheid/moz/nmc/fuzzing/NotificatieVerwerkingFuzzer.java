package nl.rijksoverheid.moz.nmc.fuzzing;

import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
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
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLAuthorizationHolder;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLJwtFactory;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLVerzendAdapter;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.ProfielServiceAdapter;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.api.ProfielApi;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.ContactgegevenResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.PartijResponse;
import nl.rijksoverheid.moz.nmc.controller.AannameLocatie;
import nl.rijksoverheid.moz.nmc.controller.CentraleNotificatieController;
import nl.rijksoverheid.moz.nmc.controller.DecentraleNotificatieController;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.OvergangUitkomst;
import nl.rijksoverheid.moz.nmc.domain.Overgangsregels;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.Reden;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.service.NotificatieNietGevondenException;
import nl.rijksoverheid.moz.nmc.service.Overgangsfunctie;
import nl.rijksoverheid.moz.nmc.service.InkomendEventOpslag;
import nl.rijksoverheid.moz.nmc.service.ReceiptVerwerker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import nl.rijksoverheid.moz.nmc.helper.HashHelper;
import nl.rijksoverheid.moz.nmc.notifynlcallback.api.model.AfleverstatusRequest;
import nl.rijksoverheid.moz.nmc.notifynlcallback.controller.NotifyNLCallbackController;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.service.KekProvider;
import nl.rijksoverheid.moz.nmc.service.AannameService;
import nl.rijksoverheid.moz.nmc.repository.DienstverlenerRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.domain.Dienstverlener;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.service.Sleutelbeheer;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
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
 * Fuzz target for ClusterFuzzLite. Runs caller-supplied JSON through Jackson, bean validation,
 * the three POST controllers, the service and the NotifyNL adapter in the fuzzer's own JVM, with
 * in-memory stand-ins for the outbound clients and the repository.
 *
 * <p>A Jackson error, a constraint violation and an {@link HttpProblem} are expected outcomes.
 * Findings: any other exception, a 5xx while every stand-in the route reaches succeeds, and a
 * delivery receipt that deletes the notificatie or takes it back to before the send.
 */
public class NotificatieVerwerkingFuzzer {

    // Shape NotifyNLJwtFactory accepts: naam-<serviceId>-<secret>, at least 74 characters.
    private static final String API_KEY =
            "niet-voor-productie-00000000-0000-0000-0000-000000000000-11111111-1111-1111-1111-111111111111";

    // Fixed ids keep the target deterministic per input.
    private static final UUID NOTIFY_REFERENTIE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PARTIJ_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final String[] IDENTIFICATIE_TYPES = {"BSN", "KVK", "RSIN", "INVALID"};
    private static final String[] BERICHT_TYPES = {"Stuurgroep Agenda", "Demo template", "onbekend"};
    private static final String[] AFLEVER_STATUSSEN = {
        "delivered", "permanent-failure", "temporary-failure", "technical-failure", "onbekend",
        "created", "sending", "pending"
    };

    // Same as the Quarkus mapper: unknown properties are ignored.
    private static final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final Validator validator = Validation.byDefaultProvider()
            .configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory()
            .getValidator();

    /** 0 = partij with an e-mail address, 1 = 404, 2 = 500. */
    private static int profielAntwoord;
    /** 0 = accepted, 1 = rejected, 2 = response without a notification id. */
    private static int notifyAntwoord;

    private static final GeheugenNotificatieRepository repository = new GeheugenNotificatieRepository();

    private static final GeheugenPogingRepository pogingen = new GeheugenPogingRepository();

    private static final GeheugenTaakRepository taken = new GeheugenTaakRepository();

    private static ReceiptVerwerker receiptVerwerker;

    private static final GeheugenOvergangsfunctie overgangsfunctie = new GeheugenOvergangsfunctie();

    private static final class VasteKekProvider implements KekProvider {

        private static final SecretKey KEK = new SecretKeySpec(new byte[32], "AES");

        @Override
        public int huidigeVersie() {
            return 1;
        }

        @Override
        public Optional<SecretKey> kek(int versie) {
            return versie == 1 ? Optional.of(KEK) : Optional.empty();
        }
    }

    private static final CentraleNotificatieController centraleController;
    private static final DecentraleNotificatieController decentraleController;
    private static final NotifyNLCallbackController callbackController;

    static {
        // Application logging off. The AssertionError in roepAan names the stand-in states.
        Logger.getLogger("").setLevel(Level.OFF);

        // The intake no longer calls the Profielservice or NotifyNL; those stand-ins belong to the
        // verzendtaak, which is outside this target.
        AannameService aannameService = new AannameService(
                overgangsfunctie,
                new Sleutelbeheer(new VasteKekProvider(), new ObjectMapper()),
                () -> NotificatieFixtures.DV_ID,
                new GeheugenDienstverlenerRepository(),
                repository,
                taken);
        receiptVerwerker = new ReceiptVerwerker(pogingen, overgangsfunctie);

        LogboekContext logboekContext = new LogboekContext();
        HashHelper hashHelper = new HashHelper(Optional.of("fuzz-pepper-niet-voor-productie"));
        AannameLocatie aannameLocatie = new AannameLocatie();

        centraleController = new CentraleNotificatieController(aannameService, logboekContext, hashHelper, aannameLocatie);
        decentraleController = new DecentraleNotificatieController(aannameService, logboekContext, hashHelper, aannameLocatie);
        callbackController = new NotifyNLCallbackController(new InkomendEventOpslag(pogingen, repository, taken,
                mapper, new SimpleMeterRegistry()));
    }

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        repository.leegmaken();
        pogingen.leegmaken();

        int route = data.consumeInt(0, 4);
        profielAntwoord = gewogenAntwoord(data);
        notifyAntwoord = gewogenAntwoord(data);
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
                    true);
        }
    }

    private static void decentraal(String json) {
        DecentraleNotificatieAanvraagRequest aanvraag = lees(json, DecentraleNotificatieAanvraagRequest.class);
        if (aanvraag != null) {
            roepAan(() -> decentraleController.decentraleNotificatieVersturen(aanvraag), true);
        }
    }

    private static void afleverstatus(String json, boolean notificatieIsBekend) {
        AfleverstatusRequest melding = lees(json, AfleverstatusRequest.class);
        if (melding == null) {
            return;
        }
        UUID bekendeId = notificatieIsBekend ? bewaarVerzonden(melding.getId()) : null;
        // A 5xx on this route is always a finding.
        roepAan(() -> callbackController.verwerkAfleverstatus(melding), true);
        // The callback only stores; process what it stored, as the receipt task would.
        taken.verwerkOpgeslagen();

        // A delivery receipt never deletes the notificatie and never takes it back to before the send.
        if (bekendeId != null) {
            Notificatie notificatie = repository.findById(bekendeId);

            if (notificatie == null) {
                throw new AssertionError("notificatie verwijderd na een receipt");
            }

            if (notificatie.getStatus() == NotificatieStatus.AANGENOMEN
                    || notificatie.getStatus() == NotificatieStatus.IN_VERZENDING) {
                throw new AssertionError("notificatie teruggezet naar " + notificatie.getStatus());
            }
        }
    }

    /**
     * Returns null for input the HTTP layer answers with a 400 before the controller runs:
     * unparseable JSON and constraint violations.
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

    /** Success weighted 4:1:1 over the three answers. */
    private static int gewogenAntwoord(FuzzedDataProvider data) {
        int keuze = data.consumeInt(0, 5);
        return keuze <= 3 ? 0 : keuze - 3;
    }

    /**
     * @param geen5xxVerwacht whether a 5xx counts as a finding; the notificatie routes pass true
     *                        when every stand-in they reach is set to succeed
     */
    private static void roepAan(Runnable aanroep, boolean geen5xxVerwacht) {
        try {
            aanroep.run();
        } catch (HttpProblem e) {
            if (e.getStatusCode() >= 500 && geen5xxVerwacht) {
                // The HttpProblem carries no cause; the message names the stand-in states.
                throw new AssertionError(
                        "5xx voor invoer die de validatie doorkwam (profielAntwoord=%d, notifyAntwoord=%d)"
                                .formatted(profielAntwoord, notifyAntwoord), e);
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
        return body.toString();
    }

    private static String decentraleAanvraag(FuzzedDataProvider data) {
        ObjectNode body = mapper.createObjectNode();
        // Half the inputs get an address that passes validation.
        body.put("emailAdres", data.consumeBoolean() ? "fuzz@example.invalid" : data.consumeString(60));
        body.put("berichtType", data.pickValue(BERICHT_TYPES));
        body.putObject("berichtgegevens").put(data.consumeString(20), data.consumeString(50));
        return body.toString();
    }

    private static String afleverstatusMelding(FuzzedDataProvider data) {
        ObjectNode body = mapper.createObjectNode();
        // id (UUID) and created_at (OffsetDateTime) get a parseable value half the time.
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

    /** Answers one operation of a generated client; every other operation throws. */
    private static <T> T standIn(Class<T> api, String methode, Supplier<Object> antwoord) {
        return api.cast(Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> api.getSimpleName() + "-stand-in";
                        };
                    }
                    if (!methode.equals(method.getName())) {
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

    /** A notificatie on VERZONDEN with one poging under the given NotifyNL id. */
    private static UUID bewaarVerzonden(UUID notifyId) {
        Notificatie notificatie = new Notificatie(NotificatieFixtures.DV_ID);
        overgangsfunctie.neemAan(notificatie);
        Poging poging = new Poging(notificatie.getId(), 1);
        pogingen.persist(poging);
        overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.IN_VERZENDING, null);
        poging.markeerVerzonden(notifyId, java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.VERZONDEN, null);

        return notificatie.getId();
    }

    /** In-memory stand-in for the pogingen; column constraints are not enforced. */
    private static final class GeheugenPogingRepository extends PogingRepository {

        private final Map<UUID, Poging> opgeslagen = new HashMap<>();

        @Override
        public void persist(Poging poging) {
            opgeslagen.put(poging.getId(), poging);
        }

        @Override
        public Optional<Poging> findByNotifyId(UUID notifyId) {
            return opgeslagen.values().stream().filter(p -> notifyId.equals(p.getNotifyId())).findFirst();
        }

        @Override
        public Poging findById(UUID id) {
            return opgeslagen.get(id);
        }

        @Override
        public Optional<Poging> findLaatsteVan(UUID notificatieId) {
            return opgeslagen.values().stream()
                    .filter(p -> notificatieId.equals(p.getNotificatieId()))
                    .max(java.util.Comparator.comparingInt(Poging::getNummer));
        }

        @Override
        public void herlaad(Poging poging) {
            // No-op; the in-memory copy is the only copy.
        }

        void leegmaken() {
            opgeslagen.clear();
        }
    }

    /** Accepts the verzendtaak the aanname plans; nothing reads it back in this target. */
    private static final class GeheugenTaakRepository extends TaakRepository {

        private final List<Map<String, String>> receipts = new java.util.ArrayList<>();

        @Override
        public void persist(Taak taak) {
            // No-op; there is no worker in this target.
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean planReceipt(UUID dvId, UUID notificatieId, String payloadJson, java.time.OffsetDateTime due) {
            try {
                receipts.add(mapper.readValue(payloadJson, Map.class));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }

            return true;
        }

        void verwerkOpgeslagen() {
            for (Map<String, String> receipt : receipts) {
                String tijdstip = receipt.get("tijdstip");
                receiptVerwerker.verwerk(UUID.fromString(receipt.get("notifyId")), receipt.get("pogingId"), receipt.get("status"),
                        tijdstip == null || tijdstip.isEmpty() ? null : java.time.OffsetDateTime.parse(tijdstip));
            }

            receipts.clear();
        }
    }

    /** One dienstverlener without a quotum, like the row from the migration. */
    private static final class GeheugenDienstverlenerRepository extends DienstverlenerRepository {

        private final Dienstverlener dienstverlener = new Dienstverlener() {
        };

        @Override
        public Dienstverlener findById(UUID id) {
            return dienstverlener;
        }
    }

    /**
     * In-memory stand-in for the transition function: applies Overgangsregels without a database,
     * lock or trigger, and numbers events per notificatie.
     */
    private static final class GeheugenOvergangsfunctie extends Overgangsfunctie {

        private final Map<UUID, Long> versies = new HashMap<>();

        GeheugenOvergangsfunctie() {
            super(null, null);
        }

        @Override
        public nl.rijksoverheid.moz.nmc.domain.Event neemAan(Notificatie notificatie) {
            notificatie.pasOvergangToe(NotificatieStatus.AANGENOMEN, null);
            repository.persist(notificatie);
            versies.put(notificatie.getId(), 0L);

            return new nl.rijksoverheid.moz.nmc.domain.Event(NotificatieFixtures.DV_ID, notificatie.getId(), 0, null, NotificatieStatus.AANGENOMEN, null);
        }

        @Override
        public Notificatie vergrendel(UUID notificatieId) {
            Notificatie notificatie = repository.findById(notificatieId);

            if (notificatie == null) {
                throw new NotificatieNietGevondenException("Geen notificatie gevonden met id " + notificatieId);
            }

            return notificatie;
        }

        @Override
        public OvergangUitkomst voerUit(UUID notificatieId, NotificatieStatus naar, Reden reden) {
            Notificatie notificatie = vergrendel(notificatieId);
            NotificatieStatus van = notificatie.getStatus();

            if (!Overgangsregels.isToegestaan(van, naar)) {
                return OvergangUitkomst.geweigerd(van, naar);
            }

            notificatie.pasOvergangToe(naar, reden);
            long versie = versies.merge(notificatieId, 1L, Long::sum);

            return OvergangUitkomst.uitgevoerd(van,
                    new nl.rijksoverheid.moz.nmc.domain.Event(NotificatieFixtures.DV_ID, notificatieId, versie, van, naar, reden));
        }
    }

    /** In-memory stand-in for the Panache repository; column constraints are not enforced. */
    private static final class GeheugenNotificatieRepository extends NotificatieRepository {

        private final Map<UUID, Notificatie> opgeslagen = new HashMap<>();

        @Override
        public void persist(Notificatie notificatie) {
            opgeslagen.put(notificatie.getId(), notificatie);
        }

        void leegmaken() {
            opgeslagen.clear();
        }

        @Override
        public void flush() {
            // No-op; nothing is buffered.
        }

        @Override
        public boolean deleteById(UUID id) {
            return opgeslagen.remove(id) != null;
        }

        @Override
        public Notificatie findById(UUID id) {
            return opgeslagen.get(id);
        }
    }
}
