package nl.rijksoverheid.moz.nmc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Slaat een binnengekomen delivery receipt op als receipttaak, zodat de callback pas 2xx antwoordt als
 * de receipt duurzaam vastligt en een fout in de verwerking geen receipt kost. Het e-mailadres uit de
 * receipt wordt niet opgeslagen.
 */
@ApplicationScoped
public class InkomendEventOpslag {

    static final String PAYLOAD_POGING_ID = "pogingId";
    static final String PAYLOAD_NOTIFY_ID = "notifyId";
    static final String PAYLOAD_STATUS = "status";
    static final String PAYLOAD_TIJDSTIP = "tijdstip";

    private final PogingRepository pogingRepository;
    private final NotificatieRepository notificatieRepository;
    private final TaakRepository taakRepository;
    private final ObjectMapper objectMapper;
    private final Counter afgewezen;

    public InkomendEventOpslag(PogingRepository pogingRepository, NotificatieRepository notificatieRepository,
                               TaakRepository taakRepository, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.pogingRepository = pogingRepository;
        this.notificatieRepository = notificatieRepository;
        this.taakRepository = taakRepository;
        this.objectMapper = objectMapper;
        this.afgewezen = Counter.builder("nmc.receipts.afgewezen")
                .description("Receipts die bij geen poging horen en niet zijn opgeslagen")
                .register(meterRegistry);
    }

    /** Wat er met een receipt is gebeurd. */
    public enum Uitkomst {
        OPGESLAGEN,
        HERHALING,
        ONBEKEND
    }

    /**
     * @param tijdstip het tijdstip van de status volgens NotifyNL; mag null zijn
     */
    @Transactional
    public Uitkomst slaOp(UUID notifyId, String reference, String status, OffsetDateTime tijdstip) {
        Optional<Poging> poging = pogingRepository.zoekVoorReceipt(notifyId, reference);

        if (poging.isEmpty()) {
            afgewezen.increment();

            return Uitkomst.ONBEKEND;
        }

        Notificatie notificatie = notificatieRepository.findById(poging.get().getNotificatieId());

        if (notificatie == null) {
            afgewezen.increment();

            return Uitkomst.ONBEKEND;
        }

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put(PAYLOAD_POGING_ID, poging.get().getId().toString());
        payload.put(PAYLOAD_NOTIFY_ID, notifyId.toString());
        payload.put(PAYLOAD_STATUS, status);
        payload.put(PAYLOAD_TIJDSTIP, tijdstip == null ? "" : tijdstip.withOffsetSameInstant(ZoneOffset.UTC).toString());

        boolean nieuw = taakRepository.planReceipt(notificatie.getDvId(), notificatie.getId(), json(payload),
                OffsetDateTime.now(ZoneOffset.UTC));

        if (!nieuw) {
            Log.debugf("Receipt %s voor poging %s staat al klaar; herhaling genegeerd", status, poging.get().getId());
        }

        return nieuw ? Uitkomst.OPGESLAGEN : Uitkomst.HERHALING;
    }

    private String json(Map<String, String> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Receiptpayload kon niet naar JSON", e);
        }
    }
}
