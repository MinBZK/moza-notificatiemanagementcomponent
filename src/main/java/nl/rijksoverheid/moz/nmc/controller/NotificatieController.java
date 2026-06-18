package nl.rijksoverheid.moz.nmc.controller;

import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext;
import nl.rijksoverheid.moz.nmc.api.NotificatiesApi;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieAanvraagRequest;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieResponse;
import nl.rijksoverheid.moz.nmc.helper.HashHelper;
import nl.rijksoverheid.moz.nmc.service.NotificatieService;

public class NotificatieController implements NotificatiesApi {

    private final NotificatieService notificatieService;
    private final LogboekContext logboekContext;
    private final HashHelper hashHelper;

    public NotificatieController(NotificatieService notificatieService, LogboekContext logboekContext, HashHelper hashHelper) {
        this.notificatieService = notificatieService;
        this.logboekContext = logboekContext;
        this.hashHelper = hashHelper;
    }

    @Override
    // TODO: processingActivityId is een placeholder, vervang door de echte URL uit het privacy-register.
    @Logboek(name = "notificatieVersturen", processingActivityId = "https://mijnoverheidzakelijk.nl/verwerkingsactiviteiten/TODO-NMC")
    public NotificatieResponse notificatieVersturen(NotificatieAanvraagRequest notificatieAanvraagRequest) {
        logboekContext.setDataSubjectId(hashHelper.hashIdentifier(notificatieAanvraagRequest.getIdentificatieNummer()));
        logboekContext.setDataSubjectType(String.valueOf(notificatieAanvraagRequest.getIdentificatieType()));

        return notificatieService.versturen(notificatieAanvraagRequest);
    }
}
