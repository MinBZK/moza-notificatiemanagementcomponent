package nl.rijksoverheid.moz.nmc.notifynlcallback.controller;

import io.quarkus.logging.Log;
import nl.rijksoverheid.moz.nmc.notifynlcallback.api.NotifyNlCallbackApi;
import nl.rijksoverheid.moz.nmc.notifynlcallback.api.model.AfleverstatusRequest;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import nl.rijksoverheid.moz.nmc.notifynlcallback.filter.NotifyNLCallbackBeveiligd;
import nl.rijksoverheid.moz.nmc.service.NotificatieNietGevondenException;
import nl.rijksoverheid.moz.nmc.service.NotificatieService;

@NotifyNLCallbackBeveiligd
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
            // Kan een late/vertraagde callback zijn voor een notificatie die de retentiejob
            // inmiddels al heeft opgeruimd (laatsteStatusUpdate ouder dan de bewaartermijn, ook als
            // er nog geen NotifyNL-uitkomst was) — zonder deze log is dat niet te onderscheiden van
            // een onbekende/foutieve referentie.
            Log.warnf("NotifyNL-callback voor onbekende of reeds verwijderde notificatie (notifyNlNotificatieId=%s)",
                    afleverstatusRequest.getId());
            throw Problems.notFound("Notificatie niet gevonden", e.getMessage());
        }
    }
}
