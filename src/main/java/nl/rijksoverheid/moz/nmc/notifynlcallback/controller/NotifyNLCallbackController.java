package nl.rijksoverheid.moz.nmc.notifynlcallback.controller;

import io.quarkus.logging.Log;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import nl.rijksoverheid.moz.nmc.notifynlcallback.api.NotifyNlCallbackApi;
import nl.rijksoverheid.moz.nmc.notifynlcallback.api.model.AfleverstatusRequest;
import nl.rijksoverheid.moz.nmc.notifynlcallback.filter.NotifyNLCallbackBeveiligd;
import nl.rijksoverheid.moz.nmc.service.NotificatieNietGevondenException;
import nl.rijksoverheid.moz.nmc.service.ReceiptVerwerker;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.stream.Stream;

@NotifyNLCallbackBeveiligd
public class NotifyNLCallbackController implements NotifyNlCallbackApi {

    private final ReceiptVerwerker receiptVerwerker;

    public NotifyNLCallbackController(ReceiptVerwerker receiptVerwerker) {
        this.receiptVerwerker = receiptVerwerker;
    }

    @Override
    public void verwerkAfleverstatus(AfleverstatusRequest afleverstatusRequest) {
        try {
            receiptVerwerker.verwerk(afleverstatusRequest.getId(), afleverstatusRequest.getStatus(),
                    gebeurtenisTijdstip(afleverstatusRequest));
        } catch (NotificatieNietGevondenException e) {
            Log.warnf("NotifyNL-callback voor onbekende notificatie (notifyNlNotificatieId=%s)",
                    afleverstatusRequest.getId());
            throw Problems.notFound("Notificatie niet gevonden", e.getMessage());
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
