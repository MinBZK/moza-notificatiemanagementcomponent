package nl.rijksoverheid.moz.nmc.controller;

import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext;
import nl.rijksoverheid.moz.nmc.api.NotificatiesApi;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieAanvraagRequest;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieResponse;
import nl.rijksoverheid.moz.nmc.client.notify.NotifyConfiguratieException;
import nl.rijksoverheid.moz.nmc.client.notify.NotifyVerzendException;
import nl.rijksoverheid.moz.nmc.client.profielservice.GeenEmailadresGevondenException;
import nl.rijksoverheid.moz.nmc.client.profielservice.PartijNietGevondenException;
import nl.rijksoverheid.moz.nmc.client.profielservice.ProfielServiceException;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.helper.HashHelper;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import nl.rijksoverheid.moz.nmc.service.NotificatieService;
import nl.rijksoverheid.moz.nmc.service.NotificatieVersturenOpdracht;

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
    // TODO: processingActivityId is een placeholder, vervang door de echte URL uit het privacy-register.
    @Logboek(name = "notificatieVersturen", processingActivityId = "https://mijnoverheidzakelijk.nl/verwerkingsactiviteiten/TODO-NMC")
    public NotificatieResponse notificatieVersturen(NotificatieAanvraagRequest notificatieAanvraagRequest) {
        logboekContext.setDataSubjectId(hashHelper.hashIdentifier(notificatieAanvraagRequest.getIdentificatieNummer()));
        logboekContext.setDataSubjectType(String.valueOf(notificatieAanvraagRequest.getIdentificatieType()));

        // TODO (security): callbackUrl is unvalidated caller input that we later POST to — SSRF risk.
        String callbackUrl = notificatieAanvraagRequest.getCallbackUrl() != null
                ? notificatieAanvraagRequest.getCallbackUrl().toString()
                : null;
        NotificatieVersturenOpdracht opdracht = new NotificatieVersturenOpdracht(
                notificatieAanvraagRequest.getIdentificatieType(),
                notificatieAanvraagRequest.getIdentificatieNummer(),
                notificatieAanvraagRequest.getDienstverlener(),
                notificatieAanvraagRequest.getDienst(),
                notificatieAanvraagRequest.getBerichtgegevens(),
                callbackUrl);

        try {
            Notificatie notificatie = notificatieService.versturen(opdracht);
            return new NotificatieResponse().notificatieId(notificatie.getId());
        } catch (PartijNietGevondenException e) {
            throw Problems.badRequest("Partij niet gevonden", e.getMessage());
        } catch (GeenEmailadresGevondenException e) {
            throw Problems.badRequest("Geen e-mailadres gevonden", e.getMessage());
        } catch (ProfielServiceException e) {
            throw Problems.serverError("Profielservice fout", e.getMessage());
        } catch (NotifyConfiguratieException e) {
            throw Problems.serverError("Verzendfout", "Er kan momenteel geen notificatie worden verstuurd");
        } catch (NotifyVerzendException e) {
            throw Problems.badGateway("Verzendfout", e.getMessage());
        }
    }
}
