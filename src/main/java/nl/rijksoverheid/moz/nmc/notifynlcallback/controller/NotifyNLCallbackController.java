package nl.rijksoverheid.moz.nmc.notifynlcallback.controller;

import nl.rijksoverheid.moz.nmc.notifynlcallback.api.NotifyNlCallbackApi;
import nl.rijksoverheid.moz.nmc.notifynlcallback.api.model.AfleverstatusRequest;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import nl.rijksoverheid.moz.nmc.service.NotificatieNietGevondenException;
import nl.rijksoverheid.moz.nmc.service.NotificatieService;

public class NotifyNLCallbackController implements NotifyNlCallbackApi {

    private final NotificatieService notificatieService;

    public NotifyNLCallbackController(NotificatieService notificatieService) {
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
