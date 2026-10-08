package nl.rijksoverheid.moz.nmc.controller;

import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext;
import nl.rijksoverheid.moz.nmc.api.WijzigingenApi;
import nl.rijksoverheid.moz.nmc.api.model.BevestigingRequest;
import nl.rijksoverheid.moz.nmc.api.model.WijzigingenPagina;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.NotificatieStatusEvent;
import nl.rijksoverheid.moz.nmc.domain.Cursor;
import nl.rijksoverheid.moz.nmc.domain.OngeldigeCursorException;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import nl.rijksoverheid.moz.nmc.service.Aanroeplimiet;
import nl.rijksoverheid.moz.nmc.service.CursorVervallenException;
import nl.rijksoverheid.moz.nmc.service.DvProvider;
import nl.rijksoverheid.moz.nmc.service.EventPagina;
import nl.rijksoverheid.moz.nmc.service.Eventfeed;

import java.util.UUID;

/**
 * De eventfeed en de bevestiging, binnen de dienstverlener uit {@link DvProvider}. Per opgevraagde
 * pagina één LDV-registratie, met de dienstverlener als betrokkene: het log is pseudoniem, maar de
 * Dienstverlener kan het herleiden.
 */
public class EventfeedController implements WijzigingenApi {

    private static final String BETROKKENE_TYPE_DIENSTVERLENER = "DIENSTVERLENER";

    private final Eventfeed eventfeed;
    private final Aanroeplimiet aanroeplimiet;
    private final DvProvider dvProvider;
    private final LogboekContext logboekContext;

    public EventfeedController(Eventfeed eventfeed, Aanroeplimiet aanroeplimiet, DvProvider dvProvider,
                               LogboekContext logboekContext) {
        this.eventfeed = eventfeed;
        this.aanroeplimiet = aanroeplimiet;
        this.dvProvider = dvProvider;
        this.logboekContext = logboekContext;
    }

    @Override
    // TODO #754 (LDV Logboek annotaties hebben placeholder URL)
    // processingActivityId is een placeholder, vervang door de echte URL uit het privacy-register.
    @Logboek(name = "wijzigingenLezen", processingActivityId = "https://mijnoverheidzakelijk.nl/verwerkingsactiviteiten/TODO-NMC")
    public WijzigingenPagina wijzigingenLezen(String cursor, Integer limiet) {
        UUID dvId = dvProvider.huidigeDvId();
        logboekContext.setDataSubjectId(dvId.toString());
        logboekContext.setDataSubjectType(BETROKKENE_TYPE_DIENSTVERLENER);

        if (!aanroeplimiet.registreer(dvId)) {
            throw Problems.tooManyRequests(Problems.TYPE_AANROEPLIMIET_OVERSCHREDEN, "Aanroeplimiet bereikt",
                    "Het maximum aantal feedaanroepen per minuut is bereikt; probeer het in de volgende minuut opnieuw");
        }

        EventPagina pagina;
        try {
            pagina = eventfeed.lees(dvId, ontleed(cursor), limiet);
        } catch (CursorVervallenException e) {
            throw Problems.gone(Problems.TYPE_CURSOR_VERVALLEN, "Cursor vervallen", e.getMessage());
        }

        return new WijzigingenPagina(pagina.events().stream().map(NotificatieStatusEvent::van).toList())
                .cursor(pagina.cursor() == null ? null : pagina.cursor().codeer());
    }

    @Override
    public void wijzigingenBevestigen(BevestigingRequest bevestigingRequest) {
        Cursor cursor = ontleed(bevestigingRequest.getCursor());

        if (cursor == null) {
            throw Problems.badRequest("Cursor ontbreekt", "Een bevestiging heeft een cursor nodig");
        }

        try {
            eventfeed.bevestig(dvProvider.huidigeDvId(), cursor);
        } catch (CursorVervallenException e) {
            throw Problems.gone(Problems.TYPE_CURSOR_VERVALLEN, "Cursor vervallen", e.getMessage());
        }
    }

    // Een lege cursor betekent: vanaf het oudste beschikbare event.
    private static Cursor ontleed(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }

        try {
            return Cursor.decodeer(cursor.strip());
        } catch (OngeldigeCursorException e) {
            throw Problems.badRequest("Ongeldige cursor", e.getMessage());
        }
    }
}
