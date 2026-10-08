package nl.rijksoverheid.moz.nmc.controller;

import io.quarkus.logging.Log;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext;
import nl.rijksoverheid.moz.nmc.api.DecentraleNotificatiesApi;
import nl.rijksoverheid.moz.nmc.api.model.DecentraleNotificatieAanvraagRequest;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieResponse;
import nl.rijksoverheid.moz.nmc.domain.Ontvanger;
import nl.rijksoverheid.moz.nmc.helper.HashHelper;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import nl.rijksoverheid.moz.nmc.service.AannameOpdracht;
import nl.rijksoverheid.moz.nmc.service.AannameService;
import nl.rijksoverheid.moz.nmc.service.BerichtType;
import nl.rijksoverheid.moz.nmc.service.CallbackUrlValidator;
import nl.rijksoverheid.moz.nmc.service.OnbekendBerichtTypeException;
import nl.rijksoverheid.moz.nmc.service.QuotumOverschredenException;
import nl.rijksoverheid.moz.nmc.service.Regie;

import java.util.UUID;

/**
 * Intake bij decentrale regie: de aanroeper levert het e-mailadres zelf. De aanname antwoordt 202
 * zodra notificatie, event en verzendtaak zijn gecommit.
 */
public class DecentraleNotificatieController implements DecentraleNotificatiesApi {

    private static final String BETROKKENE_TYPE_EMAIL = "EMAIL";

    private final AannameService aannameService;
    private final LogboekContext logboekContext;
    private final HashHelper hashHelper;
    private final AannameLocatie aannameLocatie;

    public DecentraleNotificatieController(AannameService aannameService, LogboekContext logboekContext, HashHelper hashHelper,
                                           AannameLocatie aannameLocatie) {
        this.aannameService = aannameService;
        this.logboekContext = logboekContext;
        this.hashHelper = hashHelper;
        this.aannameLocatie = aannameLocatie;
    }

    @Override
    // TODO #754 (LDV Logboek annotaties hebben placeholder URL)
    // processingActivityId is een placeholder, vervang door de echte URL uit het privacy-register.
    @Logboek(name = "decentraleNotificatieAannemen", processingActivityId = "https://mijnoverheidzakelijk.nl/verwerkingsactiviteiten/TODO-NMC")
    @Aanname
    public NotificatieResponse decentraleNotificatieVersturen(DecentraleNotificatieAanvraagRequest request) {
        // TODO #758 (Logboek context setten via annotatie ipv in method body)
        // Betrokkene-id eerst zetten: de @Logboek-interceptor eist een niet-lege data_subject_id.
        logboekContext.setDataSubjectId(hashHelper.hashIdentifier(request.getEmailAdres()));
        logboekContext.setDataSubjectType(BETROKKENE_TYPE_EMAIL);

        try {
            UUID id = aannameService.neemAan(opdracht(request));
            aannameLocatie.zet(id);

            return new NotificatieResponse(id);
        } catch (OnbekendBerichtTypeException e) {
            throw Problems.badRequest("Notificatie niet aangenomen.", e.getMessage());
        } catch (QuotumOverschredenException e) {
            throw Problems.quotumOverschreden(e.getMessage());
        } catch (Exception e) {
            Log.error("Onverwachte fout bij het aannemen van een decentrale notificatie", e);
            throw Problems.serverError("Aannamefout", "Er kan momenteel geen notificatie worden aangenomen");
        }
    }

    private static AannameOpdracht opdracht(DecentraleNotificatieAanvraagRequest request) {
        return new AannameOpdracht(
                Regie.DECENTRAAL,
                Ontvanger.email(request.getEmailAdres()),
                null,
                null,
                BerichtType.vanNaam(request.getBerichtType()).getTemplateId(),
                request.getBerichtgegevens(),
                CallbackUrlValidator.normaliseer(request.getCallbackUrl()));
    }
}
