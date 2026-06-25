package nl.rijksoverheid.moz.nmc.notifycallback.controller;

import nl.rijksoverheid.moz.nmc.notifycallback.api.NotifyCallbackApi;
import nl.rijksoverheid.moz.nmc.notifycallback.api.model.AfleverstatusRequest;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import nl.rijksoverheid.moz.nmc.service.NotificatieNietGevondenException;
import nl.rijksoverheid.moz.nmc.service.NotificatieService;

// TODO #755 (NMC: Authenticatie nodig bij callback endpoint voor NotifyNL richting NMC)
// (security): this endpoint has no authentication. GOV.UK Notify supports a callback
// bearer token that should be validated here to prevent callers from submitting fake delivery receipts.
public class NotifyCallbackController implements NotifyCallbackApi {

    private final NotificatieService notificatieService;

    public NotifyCallbackController(NotificatieService notificatieService) {
        this.notificatieService = notificatieService;
    }

    @Override
    public void verwerkAfleverstatus(AfleverstatusRequest afleverstatusRequest) {
        try {
            notificatieService.verwerkAfleverstatus(afleverstatusRequest.getId(), afleverstatusRequest.getStatus());
        } catch (NotificatieNietGevondenException e) {
            throw Problems.notFound("Notificatie niet gevonden", e.getMessage());
        }
    }
}
