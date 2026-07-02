package nl.rijksoverheid.moz.nmc.controller;

import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext;
import nl.rijksoverheid.moz.nmc.api.NotificatiesApi;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieAanvraagRequest;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.GeenEmailadresGevondenException;
import nl.rijksoverheid.moz.nmc.client.profielservice.PartijNietGevondenException;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.helper.HashHelper;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import nl.rijksoverheid.moz.nmc.service.NotificatieException;
import nl.rijksoverheid.moz.nmc.service.NotificatieService;
import nl.rijksoverheid.moz.nmc.service.NotificatieVersturenOpdracht;
import org.jspecify.annotations.NonNull;

public class CentraleNotificatieController implements NotificatiesApi {

    private final NotificatieService notificatieService;
    private final LogboekContext logboekContext;
    private final HashHelper hashHelper;

    public CentraleNotificatieController(NotificatieService notificatieService, LogboekContext logboekContext, HashHelper hashHelper) {
        this.notificatieService = notificatieService;
        this.logboekContext = logboekContext;
        this.hashHelper = hashHelper;
    }

    @Override
    // TODO #754 (LDV Logboek annotaties hebben placeholder URL)
    // processingActivityId is een placeholder, vervang door de echte URL uit het privacy-register.
    @Logboek(name = "notificatieVersturen", processingActivityId = "https://mijnoverheidzakelijk.nl/verwerkingsactiviteiten/TODO-NMC")
    public NotificatieResponse notificatieVersturen(NotificatieAanvraagRequest notificatieAanvraagRequest) {
        // TODO #758 (Logboek context setten via annotatie ipv in method body)
        logboekContext.setDataSubjectId(hashHelper.hashIdentifier(notificatieAanvraagRequest.getIdentificatieNummer()));
        logboekContext.setDataSubjectType(String.valueOf(notificatieAanvraagRequest.getIdentificatieType()));

        // TODO #752 (zie NMC: CallbackUrl wordt niet gevalideerd (SSRF risico))
        // (security): callbackUrl is unvalidated caller input that we later POST to — SSRF risk.
        NotificatieVersturenOpdracht opdracht = getNotificatieVersturenOpdracht(notificatieAanvraagRequest);

        try {
            Notificatie notificatie = notificatieService.versturen(opdracht);
            return new NotificatieResponse(notificatie.getId());
        } catch (PartijNietGevondenException | GeenEmailadresGevondenException e) {
            throw Problems.badRequest("Notificatie niet verstuurd.", e.getMessage());
        } catch (Exception e) {
            throw Problems.serverError("Verzendfout", "Er kan momenteel geen notificatie worden verstuurd");
        }
    }

    private static @NonNull NotificatieVersturenOpdracht getNotificatieVersturenOpdracht(NotificatieAanvraagRequest notificatieAanvraagRequest) {
        String callbackUrl = notificatieAanvraagRequest.getCallbackUrl() != null
                ? notificatieAanvraagRequest.getCallbackUrl().toString()
                : null;
        return new NotificatieVersturenOpdracht(
                notificatieAanvraagRequest.getIdentificatieType(),
                notificatieAanvraagRequest.getIdentificatieNummer(),
                notificatieAanvraagRequest.getDienstverlener(),
                notificatieAanvraagRequest.getDienst(),
                notificatieAanvraagRequest.getBerichtgegevens(),
                callbackUrl);
    }
}
