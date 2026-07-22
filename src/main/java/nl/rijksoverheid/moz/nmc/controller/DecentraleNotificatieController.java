package nl.rijksoverheid.moz.nmc.controller;

import io.quarkus.logging.Log;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext;
import nl.rijksoverheid.moz.nmc.api.DecentraleNotificatiesApi;
import nl.rijksoverheid.moz.nmc.api.model.DecentraleNotificatieAanvraagRequest;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieResponse;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.helper.HashHelper;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import nl.rijksoverheid.moz.nmc.service.BerichtType;
import nl.rijksoverheid.moz.nmc.service.DecentraleNotificatieVersturenOpdracht;
import nl.rijksoverheid.moz.nmc.service.NotificatieService;
import nl.rijksoverheid.moz.nmc.service.OnbekendBerichtTypeException;
import org.jspecify.annotations.NonNull;

public class DecentraleNotificatieController implements DecentraleNotificatiesApi {

    private static final String BETROKKENE_TYPE_EMAIL = "EMAIL";

    private final NotificatieService notificatieService;
    private final LogboekContext logboekContext;
    private final HashHelper hashHelper;

    public DecentraleNotificatieController(NotificatieService notificatieService, LogboekContext logboekContext, HashHelper hashHelper) {
        this.notificatieService = notificatieService;
        this.logboekContext = logboekContext;
        this.hashHelper = hashHelper;
    }

    @Override
    // TODO #754 (LDV Logboek annotaties hebben placeholder URL)
    // processingActivityId is een placeholder, vervang door de echte URL uit het privacy-register.
    @Logboek(name = "decentraleNotificatieVersturen", processingActivityId = "https://mijnoverheidzakelijk.nl/verwerkingsactiviteiten/TODO-NMC")
    public NotificatieResponse decentraleNotificatieVersturen(DecentraleNotificatieAanvraagRequest request) {
        // TODO #758 (Logboek context setten via annotatie ipv in method body)
        // Betrokkene-id eerst zetten: de @Logboek-interceptor eist een niet-lege data_subject_id.
        logboekContext.setDataSubjectId(hashHelper.hashIdentifier(request.getEmailAdres()));
        logboekContext.setDataSubjectType(BETROKKENE_TYPE_EMAIL);

        try {
            Notificatie notificatie = notificatieService.verstuurDecentraal(getOpdracht(request));
            return new NotificatieResponse(notificatie.getId());
        } catch (OnbekendBerichtTypeException e) {
            throw Problems.badRequest("Notificatie niet verstuurd.", e.getMessage());
        } catch (Exception e) {
            Log.error("Onverwachte fout bij versturen van decentrale notificatie", e);
            throw Problems.serverError("Verzendfout", "Er kan momenteel geen notificatie worden verstuurd");
        }
    }

    private static @NonNull DecentraleNotificatieVersturenOpdracht getOpdracht(DecentraleNotificatieAanvraagRequest request) {
        // TODO #752 (zie NMC: CallbackUrl wordt niet gevalideerd (SSRF risico))
        // (security): callbackUrl is unvalidated caller input that we later POST to — SSRF risk.
        String callbackUrl = request.getCallbackUrl() != null ? request.getCallbackUrl().toString() : null;
        String templateId = BerichtType.vanNaam(request.getBerichtType()).getTemplateId();

        return new DecentraleNotificatieVersturenOpdracht(
                request.getEmailAdres(),
                templateId,
                request.getBerichtgegevens(),
                callbackUrl);
    }
}
