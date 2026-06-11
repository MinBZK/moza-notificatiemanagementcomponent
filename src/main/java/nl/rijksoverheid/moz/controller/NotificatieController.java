package nl.rijksoverheid.moz.controller;

import nl.rijksoverheid.moz.api.NotificatiesApi;
import nl.rijksoverheid.moz.api.model.NotificatieAanvraagRequest;
import nl.rijksoverheid.moz.api.model.NotificatieResponse;
import nl.rijksoverheid.moz.service.NotificatieService;

public class NotificatieController implements NotificatiesApi {

    private final NotificatieService notificatieService;

    public NotificatieController(NotificatieService notificatieService) {
        this.notificatieService = notificatieService;
    }

    @Override
    public NotificatieResponse notificatieVersturen(NotificatieAanvraagRequest notificatieAanvraagRequest) {
        return notificatieService.versturen(notificatieAanvraagRequest);
    }
}
