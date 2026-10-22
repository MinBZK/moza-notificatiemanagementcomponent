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
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext;
import nl.rijksoverheid.moz.nmc.api.model.DecentraleNotificatieAanvraagRequest;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieAanvraagRequest;
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
import nl.rijksoverheid.moz.nmc.service.ReceiptOpslag;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fuzz target for ClusterFuzzLite. Runs caller-supplied JSON through Jackson, bean validation,
 * the three POST controllers, the aanname and the receiptverwerking in the fuzzer's own JVM, with
 * in-memory stand-ins for the repositories.
 *
 * <p>A Jackson error, a constraint violation and an {@link HttpProblem} are expected outcomes.
 * Findings: any other exception, a 5xx for input that passed validation, and a delivery receipt
 * that deletes the notificatie or takes it back to before the send.
 */
public class NotificatieVerwerkingFuzzer {

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
        // Application logging off.
        Logger.getLogger("").setLevel(Level.OFF);

        // De aanname roept de Profielservice en NotifyNL niet meer aan; dat doet de verzendtaak, die
        // buiten dit target valt.
        AannameService aannameService = new AannameService(
                overgangsfunctie,
                new Sleutelbeheer(new VasteKekProvider(), new ObjectMapper()),
                () -> NotificatieFixtures.DV_ID,
                new GeheugenDienstverlenerRepository(),
                repository,
                taken,
                Optional.empty());
        receiptVerwerker = new ReceiptVerwerker(pogingen, overgangsfunctie, java.time.Duration.ofMinutes(5));

        LogboekContext logboekContext = new LogboekContext();
        HashHelper hashHelper = new HashHelper(Optional.of("fuzz-pepper-niet-voor-productie"));

        centraleController = new CentraleNotificatieController(aannameService, logboekContext, hashHelper);
        decentraleController = new DecentraleNotificatieController(aannameService, logboekContext, hashHelper);
        callbackController = new NotifyNLCallbackController(new ReceiptOpslag(pogingen, repository, taken,
                new SimpleMeterRegistry()));
    }

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        repository.leegmaken();
        pogingen.leegmaken();

        int route = data.consumeInt(0, 4);
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
            roepAan(() -> centraleController.notificatieVersturen(aanvraag));
        }
    }

    private static void decentraal(String json) {
        DecentraleNotificatieAanvraagRequest aanvraag = lees(json, DecentraleNotificatieAanvraagRequest.class);
        if (aanvraag != null) {
            roepAan(() -> decentraleController.decentraleNotificatieVersturen(aanvraag));
        }
    }

    private static void afleverstatus(String json, boolean notificatieIsBekend) {
        AfleverstatusRequest melding = lees(json, AfleverstatusRequest.class);
        if (melding == null) {
            return;
        }

        UUID bekendeId = notificatieIsBekend ? bewaarVerzonden(melding.getId()) : null;
        // A 5xx on this route is always a finding.
        roepAan(() -> callbackController.verwerkAfleverstatus(melding));
        // De callback slaat alleen op; verwerk wat hij opsloeg, zoals de receipttaak zou doen.
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

    private static void roepAan(Runnable aanroep) {
        try {
            aanroep.run();
        } catch (HttpProblem e) {
            if (e.getStatusCode() >= 500) {
                throw new AssertionError("5xx voor invoer die de validatie doorkwam", e);
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

    /** Een notificatie op VERZONDEN met één poging onder het gegeven NotifyNL-id. */
    private static UUID bewaarVerzonden(UUID notifyId) {
        Notificatie notificatie = new Notificatie(NotificatieFixtures.DV_ID);
        overgangsfunctie.neemAan(notificatie);
        Poging poging = new GeheugenPoging(notificatie.getId(), 1);
        pogingen.persist(poging);
        overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.IN_VERZENDING, null);
        poging.markeerVerzonden(notifyId, OffsetDateTime.now(ZoneOffset.UTC));
        overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.VERZONDEN, null);

        return notificatie.getId();
    }

    /** Pogingen in het geheugen; kolomconstraints worden niet afgedwongen. */
    /**
     * Een poging met een id vanaf het aanmaken. In productie deelt Hibernate het id uit bij persist; dit
     * target heeft geen Hibernate.
     */
    private static final class GeheugenPoging extends Poging {

        private final UUID id = UUID.randomUUID();

        GeheugenPoging(UUID notificatieId, int nummer) {
            super(notificatieId, nummer);
        }

        @Override
        public UUID getId() {
            return id;
        }
    }

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
                    .max(Comparator.comparingInt(Poging::getNummer));
        }

        @Override
        public void herlaad(Poging poging) {
            // Niets te doen: de kopie in het geheugen is de enige.
        }

        void leegmaken() {
            opgeslagen.clear();
        }
    }

    /** Neemt de verzendtaak aan die de aanname plant en bewaart receipttaken tot {@link #verwerkOpgeslagen()}. */
    private static final class GeheugenTaakRepository extends TaakRepository {

        private final List<Map<String, String>> receipts = new ArrayList<>();

        @Override
        public void persist(Taak taak) {
            // Niets te doen: dit target heeft geen worker.
        }

        @Override
        public boolean planReceipt(UUID dvId, UUID notificatieId, Map<String, String> payload, OffsetDateTime due) {
            receipts.add(payload);

            return true;
        }

        void verwerkOpgeslagen() {
            for (Map<String, String> receipt : receipts) {
                String tijdstip = receipt.get("tijdstip");
                receiptVerwerker.verwerk(UUID.fromString(receipt.get("notifyId")), receipt.get("pogingId"), receipt.get("status"),
                        tijdstip == null || tijdstip.isEmpty() ? null : OffsetDateTime.parse(tijdstip));
            }

            receipts.clear();
        }
    }

    /** Eén dienstverlener zonder quotum, zoals de rij uit de migratie. */
    private static final class GeheugenDienstverlenerRepository extends DienstverlenerRepository {

        private final Dienstverlener dienstverlener = new Dienstverlener() {
        };

        @Override
        public Dienstverlener findById(UUID id) {
            return dienstverlener;
        }
    }

    /**
     * De overgangsfunctie in het geheugen: past Overgangsregels toe zonder database, lock of trigger,
     * en nummert de events per notificatie. Event-ids komen uit een teller, zoals de identity van de
     * database ze uitdeelt; het CloudEvent gebruikt het id als id.
     */
    private static final class GeheugenOvergangsfunctie extends Overgangsfunctie {

        private final Map<UUID, Long> versies = new HashMap<>();
        private long volgendEventId = 1;

        GeheugenOvergangsfunctie() {
            super(null, null);
        }

        @Override
        public nl.rijksoverheid.moz.nmc.domain.Event neemAan(Notificatie notificatie) {
            notificatie.pasOvergangToe(NotificatieStatus.AANGENOMEN, null);
            repository.persist(notificatie);
            versies.put(notificatie.getId(), 0L);

            return event(notificatie.getId(), 0, null, NotificatieStatus.AANGENOMEN, null);
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

            return OvergangUitkomst.uitgevoerd(van, event(notificatieId, versie, van, naar, reden));
        }

        private nl.rijksoverheid.moz.nmc.domain.Event event(UUID notificatieId, long volgnummer, NotificatieStatus van,
                                                            NotificatieStatus naar, Reden reden) {
            long id = volgendEventId++;

            return new nl.rijksoverheid.moz.nmc.domain.Event(NotificatieFixtures.DV_ID, notificatieId, volgnummer, van, naar, reden) {
                @Override
                public Long getId() {
                    return id;
                }
            };
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
