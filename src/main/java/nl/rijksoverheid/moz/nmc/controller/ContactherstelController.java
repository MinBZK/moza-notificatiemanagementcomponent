package nl.rijksoverheid.moz.nmc.controller;

import io.quarkiverse.httpproblem.HttpProblem;
import jakarta.ws.rs.core.Response;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext;
import nl.rijksoverheid.moz.nmc.api.contactherstel.ContactherstelApi;
import nl.rijksoverheid.moz.nmc.api.contactherstel.model.ContactherstelStartRequest;
import nl.rijksoverheid.moz.nmc.api.contactherstel.model.ContactherstelWijzigingRequest;
import nl.rijksoverheid.moz.nmc.helper.HashHelper;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import nl.rijksoverheid.moz.nmc.service.DvProvider;

import java.util.UUID;

/**
 * Contactherstel starten, opvragen en afronden of intrekken. Het contract staat vast, de uitvoering
 * is er nog niet: elke operatie antwoordt 501, na het zetten van de betrokkene voor het logboek.
 */
public class ContactherstelController implements ContactherstelApi {

    // TODO placeholder; vervang door de URL van de verwerkingsactiviteit uit het register van
    // verwerkingsactiviteiten zodra die er is.
    private static final String VERWERKINGSACTIVITEIT = "https://mijnoverheidzakelijk.nl/verwerkingsactiviteiten/TODO-NMC";
    private static final String BETROKKENE_TYPE_DIENSTVERLENER = "DIENSTVERLENER";

    private final LogboekContext logboekContext;
    private final HashHelper hashHelper;
    private final DvProvider dvProvider;

    public ContactherstelController(LogboekContext logboekContext, HashHelper hashHelper, DvProvider dvProvider) {
        this.logboekContext = logboekContext;
        this.hashHelper = hashHelper;
        this.dvProvider = dvProvider;
    }

    @Override
    @Logboek(name = "contactherstelStarten", processingActivityId = VERWERKINGSACTIVITEIT)
    public Response contactherstelStarten(ContactherstelStartRequest contactherstelStartRequest) {
        logboekContext.setDataSubjectId(hashHelper.hashIdentifier(contactherstelStartRequest.getIdentificatieNummer()));
        logboekContext.setDataSubjectType(String.valueOf(contactherstelStartRequest.getIdentificatieType()));

        throw nietGeimplementeerd("Contactherstel starten");
    }

    @Override
    @Logboek(name = "contactherstelOpvragen", processingActivityId = VERWERKINGSACTIVITEIT)
    public Response contactherstelOpvragen(UUID contactherstelId) {
        zetDienstverlenerAlsBetrokkene();

        throw nietGeimplementeerd("Contactherstel opvragen");
    }

    @Override
    @Logboek(name = "contactherstelBijwerken", processingActivityId = VERWERKINGSACTIVITEIT)
    public Response contactherstelBijwerken(UUID contactherstelId,
                                            ContactherstelWijzigingRequest contactherstelWijzigingRequest) {
        zetDienstverlenerAlsBetrokkene();

        throw nietGeimplementeerd("Contactherstel afronden of intrekken");
    }

    // TODO de ontvanger van het contactherstel is de betrokkene; die is pas op te zoeken als het
    // contactherstel wordt opgeslagen. Tot dan de dienstverlener.
    private void zetDienstverlenerAlsBetrokkene() {
        logboekContext.setDataSubjectId(dvProvider.huidigeDvId().toString());
        logboekContext.setDataSubjectType(BETROKKENE_TYPE_DIENSTVERLENER);
    }

    private static HttpProblem nietGeimplementeerd(String operatie) {
        return Problems.notImplemented("Niet geïmplementeerd", operatie + " is nog niet geïmplementeerd");
    }
}
