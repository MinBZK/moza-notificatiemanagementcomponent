package nl.rijksoverheid.moz.nmc.controller;

import io.quarkus.logging.Log;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext;
import nl.rijksoverheid.moz.nmc.api.CentraleNotificatiesApi;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieAanvraagRequest;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieResponse;
import nl.rijksoverheid.moz.nmc.domain.Ontvanger;
import nl.rijksoverheid.moz.nmc.helper.HashHelper;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import nl.rijksoverheid.moz.nmc.service.AannameOpdracht;
import nl.rijksoverheid.moz.nmc.service.AannameService;
import nl.rijksoverheid.moz.nmc.service.BerichtType;
import nl.rijksoverheid.moz.nmc.service.OnbekendBerichtTypeException;
import nl.rijksoverheid.moz.nmc.service.QuotumOverschredenException;
import nl.rijksoverheid.moz.nmc.service.Regie;

import java.util.UUID;

/**
 * Intake bij centrale regie: de aanroeper levert een identificerend nummer, het NMC haalt het adres
 * pas in de verzendtaak op. De aanname antwoordt 202 zodra notificatie, event en verzendtaak zijn
 * gecommit.
 */
public class CentraleNotificatieController implements CentraleNotificatiesApi {

    private final AannameService aannameService;
    private final LogboekContext logboekContext;
    private final HashHelper hashHelper;
    private final AannameLocatie aannameLocatie;

    public CentraleNotificatieController(AannameService aannameService, LogboekContext logboekContext, HashHelper hashHelper,
                                         AannameLocatie aannameLocatie) {
        this.aannameService = aannameService;
        this.logboekContext = logboekContext;
        this.hashHelper = hashHelper;
        this.aannameLocatie = aannameLocatie;
    }

    @Override
    // TODO #754 (LDV Logboek annotaties hebben placeholder URL)
    // processingActivityId is een placeholder, vervang door de echte URL uit het privacy-register.
    @Logboek(name = "notificatieAannemen", processingActivityId = "https://mijnoverheidzakelijk.nl/verwerkingsactiviteiten/TODO-NMC")
    @Aanname
    public NotificatieResponse notificatieVersturen(NotificatieAanvraagRequest notificatieAanvraagRequest) {
        // TODO #758 (Logboek context setten via annotatie ipv in method body)
        logboekContext.setDataSubjectId(hashHelper.hashIdentifier(notificatieAanvraagRequest.getIdentificatieNummer()));
        logboekContext.setDataSubjectType(String.valueOf(notificatieAanvraagRequest.getIdentificatieType()));

        try {
            UUID id = aannameService.neemAan(opdracht(notificatieAanvraagRequest));
            aannameLocatie.zet(id);

            return new NotificatieResponse(id);
        } catch (OnbekendBerichtTypeException e) {
            throw Problems.badRequest("Notificatie niet aangenomen.", e.getMessage());
        } catch (QuotumOverschredenException e) {
            throw Problems.quotumOverschreden(e.getMessage());
        } catch (Exception e) {
            Log.error("Onverwachte fout bij het aannemen van een notificatie", e);
            throw Problems.serverError("Aannamefout", "Er kan momenteel geen notificatie worden aangenomen");
        }
    }

    private static AannameOpdracht opdracht(NotificatieAanvraagRequest request) {
        return new AannameOpdracht(
                Regie.CENTRAAL,
                new Ontvanger(Ontvanger.Soort.valueOf(request.getIdentificatieType().name()), request.getIdentificatieNummer()),
                request.getDienstverlener(),
                request.getDienst(),
                BerichtType.vanNaam(request.getBerichtType()),
                request.getBerichtgegevens());
    }
}
