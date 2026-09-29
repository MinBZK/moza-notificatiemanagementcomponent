package nl.rijksoverheid.moz.nmc.notifynlcallback.controller;

import io.quarkus.logging.Log;
import nl.rijksoverheid.moz.nmc.notifynlcallback.api.NotifyNlCallbackApi;
import nl.rijksoverheid.moz.nmc.notifynlcallback.api.model.AfleverstatusRequest;
import nl.rijksoverheid.moz.nmc.notifynlcallback.filter.NotifyNLCallbackBeveiligd;
import nl.rijksoverheid.moz.nmc.service.InkomendEventOpslag;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Ontvangt delivery receipts van NotifyNL en slaat ze op; de verwerking volgt als taak. Een receipt die
 * bij geen poging hoort krijgt ook een 2xx, zodat NotifyNL hem niet herhaalt; hij wordt geteld.
 */
@NotifyNLCallbackBeveiligd
public class NotifyNLCallbackController implements NotifyNlCallbackApi {

    private final InkomendEventOpslag inkomendEventOpslag;

    public NotifyNLCallbackController(InkomendEventOpslag inkomendEventOpslag) {
        this.inkomendEventOpslag = inkomendEventOpslag;
    }

    @Override
    public void verwerkAfleverstatus(AfleverstatusRequest afleverstatusRequest) {
        InkomendEventOpslag.Uitkomst uitkomst = inkomendEventOpslag.slaOp(afleverstatusRequest.getId(),
                afleverstatusRequest.getReference(), afleverstatusRequest.getStatus(), gebeurtenisTijdstip(afleverstatusRequest));

        if (uitkomst == InkomendEventOpslag.Uitkomst.ONBEKEND) {
            Log.warnf("NotifyNL-receipt hoort bij geen poging (notifyNlNotificatieId=%s); niet opgeslagen",
                    afleverstatusRequest.getId());
        }
    }

    // completed_at is het tijdstip van déze status; sent_at en created_at zijn terugval, en geen van
    // drieën is verplicht, dus null als niets bruikbaar is.
    private static OffsetDateTime gebeurtenisTijdstip(AfleverstatusRequest afleverstatusRequest) {
        return Stream.of(afleverstatusRequest.getCompletedAt(), afleverstatusRequest.getSentAt(),
                        afleverstatusRequest.getCreatedAt())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
