package nl.rijksoverheid.moz.nmc.notifycallback.controller;

import nl.rijksoverheid.moz.nmc.notifycallback.api.NotifyCallbackApi;
import nl.rijksoverheid.moz.nmc.notifycallback.api.model.AfleverstatusRequest;
import nl.rijksoverheid.moz.nmc.service.NotificatieService;

// TODO (security): this endpoint has no authentication. GOV.UK Notify supports a callback
// bearer token that should be validated here to prevent callers from submitting fake delivery receipts.
public class NotifyCallbackController implements NotifyCallbackApi {

    private final NotificatieService notificatieService;

    public NotifyCallbackController(NotificatieService notificatieService) {
        this.notificatieService = notificatieService;
    }

    @Override
    public void verwerkAfleverstatus(AfleverstatusRequest afleverstatusRequest) {
        notificatieService.verwerkAfleverstatus(afleverstatusRequest);
    }
}
