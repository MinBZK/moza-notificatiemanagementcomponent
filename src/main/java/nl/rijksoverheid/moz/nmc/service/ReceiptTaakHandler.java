package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Verwerkt een opgeslagen receipt via {@link ReceiptVerwerker}. Verwerking en afronden van de taak
 * committen samen, zodat een fout de receipt niet kost: de taak blijft staan en komt terug.
 */
@ApplicationScoped
public class ReceiptTaakHandler implements TaakHandler {

    private final ReceiptVerwerker receiptVerwerker;
    private final PogingRepository pogingRepository;
    private final TaakClaimer taakClaimer;

    public ReceiptTaakHandler(ReceiptVerwerker receiptVerwerker, PogingRepository pogingRepository, TaakClaimer taakClaimer) {
        this.receiptVerwerker = receiptVerwerker;
        this.pogingRepository = pogingRepository;
        this.taakClaimer = taakClaimer;
    }

    @Override
    public TaakSoort soort() {
        return TaakSoort.RECEIPT_VERWERKEN;
    }

    @Override
    public TaakUitkomst voerUit(Taak taak, Lease lease) {
        Map<String, String> payload = taak.getPayload();
        String pogingId = payload.get(InkomendEventOpslag.PAYLOAD_POGING_ID);
        UUID notifyId = UUID.fromString(payload.get(InkomendEventOpslag.PAYLOAD_NOTIFY_ID));
        String tijdstip = payload.get(InkomendEventOpslag.PAYLOAD_TIJDSTIP);

        QuarkusTransaction.requiringNew().run(() -> {
            // De notificatie kan inmiddels door de retentie zijn verwijderd; dan valt er niets te verwerken.
            if (pogingRepository.findById(UUID.fromString(pogingId)) == null) {
                Log.infof("Receipt voor poging %s die niet meer bestaat; taak %d afgerond", pogingId, taak.getId());
            } else {
                receiptVerwerker.verwerk(notifyId, pogingId, payload.get(InkomendEventOpslag.PAYLOAD_STATUS),
                        tijdstip == null || tijdstip.isEmpty() ? null : OffsetDateTime.parse(tijdstip));
            }

            taakClaimer.rondAf(taak);
        });

        return TaakUitkomst.alAfgerond();
    }
}
