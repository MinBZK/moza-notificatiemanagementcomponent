package nl.rijksoverheid.moz.nmc.notifynlcallback.controller;

import io.quarkus.logging.Log;
import nl.rijksoverheid.moz.nmc.notifynlcallback.api.NotifyNlCallbackApi;
import nl.rijksoverheid.moz.nmc.notifynlcallback.api.model.AfleverstatusRequest;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import nl.rijksoverheid.moz.nmc.notifynlcallback.filter.NotifyNLCallbackBeveiligd;
import nl.rijksoverheid.moz.nmc.service.NotificatieNietGevondenException;
import nl.rijksoverheid.moz.nmc.service.NotificatieService;

import java.time.OffsetDateTime;
import java.util.stream.Stream;

@NotifyNLCallbackBeveiligd
public class NotifyNLCallbackController implements NotifyNlCallbackApi {

    private final NotificatieService notificatieService;

    public NotifyNLCallbackController(NotificatieService notificatieService) {
        this.notificatieService = notificatieService;
    }

    @Override
    public void verwerkAfleverstatus(AfleverstatusRequest afleverstatusRequest) {
        try {
            notificatieService.verwerkAfleverstatus(afleverstatusRequest.getId(), afleverstatusRequest.getStatus(),
                    gebeurtenisTijdstip(afleverstatusRequest));
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

    // Wanneer de gemelde status bij NotifyNL ontstond. completed_at is "the last time the status was
    // updated" en dus het tijdstip van déze status; sent_at en created_at horen bij de verzending en
    // zijn alleen terugval. Geen van drieën is verplicht in NotifyNL's eigen callbackschema
    // (EmailCallbackRequest in notifynl_api.yaml kent geen required, en completed_at/sent_at mogen
    // expliciet null zijn), vandaar de keten en een null als niets bruikbaar is: NotificatieService
    // valt dan terug op de eigen klok. Bewust hier en niet in de service — welk veld van NotifyNL wat
    // betekent, is kennis van dit koppelvlak.
    private static OffsetDateTime gebeurtenisTijdstip(AfleverstatusRequest afleverstatusRequest) {
        return Stream.of(afleverstatusRequest.getCompletedAt(), afleverstatusRequest.getSentAt(),
                        afleverstatusRequest.getCreatedAt())
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
