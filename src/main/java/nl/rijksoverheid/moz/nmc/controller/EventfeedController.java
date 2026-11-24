package nl.rijksoverheid.moz.nmc.controller;

import io.quarkus.logging.Log;
import jakarta.ws.rs.core.Response;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext;
import nl.rijksoverheid.moz.nmc.api.EventsApi;
import nl.rijksoverheid.moz.nmc.api.model.BevestigingRequest;
import nl.rijksoverheid.moz.nmc.api.model.EventsResponse;
import nl.rijksoverheid.moz.nmc.client.webhook.NotificatieStatusEvent;
import nl.rijksoverheid.moz.nmc.domain.Cursor;
import nl.rijksoverheid.moz.nmc.domain.OngeldigeCursorException;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import nl.rijksoverheid.moz.nmc.service.Aanroeplimiet;
import nl.rijksoverheid.moz.nmc.service.CursorVervallenException;
import nl.rijksoverheid.moz.nmc.service.DvProvider;
import nl.rijksoverheid.moz.nmc.service.EventPagina;
import nl.rijksoverheid.moz.nmc.service.Eventfeed;

import java.util.List;
import java.util.UUID;

/**
 * De eventfeed en de bevestiging, binnen de dienstverlener uit {@link DvProvider}.
 */
public class EventfeedController implements EventsApi {

    // TODO vervang door een registratie per event met de ontvanger van de notificatie als betrokkene.
    private static final String BETROKKENE_ONBEKEND = "onbekend";
    private static final String BETROKKENE_TYPE_ONBEKEND = "ONBEKEND";

    // Package-private, zodat de test dezelfde tekst toetst die het antwoord draagt.
    static final String CURSOR_VERVALLEN_DETAIL = "De cursor is vervallen; lees de feed opnieuw zonder cursor";

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
    // TODO processingActivityId is een placeholder; vervang door de URL van de verwerkingsactiviteit uit
    // het register van verwerkingsactiviteiten zodra die er is.
    @Logboek(name = "eventsLezen", processingActivityId = "https://mijnoverheidzakelijk.nl/verwerkingsactiviteiten/TODO-NMC")
    public Response eventsLezen(String cursor, Integer limiet) {
        UUID dvId = dvProvider.huidigeDvId();
        logboekContext.setDataSubjectId(BETROKKENE_ONBEKEND);
        logboekContext.setDataSubjectType(BETROKKENE_TYPE_ONBEKEND);

        eisBinnenAanroeplimiet(dvId);

        EventPagina pagina;
        try {
            pagina = eventfeed.lees(dvId, ontleed(cursor), limiet);
        } catch (CursorVervallenException e) {
            throw cursorVervallen(dvId, e);
        }

        List<NotificatieStatusEvent> events = pagina.events().stream()
                .map(event -> NotificatieStatusEvent.of(event, eventfeed.source()))
                .toList();
        EventsResponse antwoord = new EventsResponse(events)
                .cursor(pagina.cursor() == null ? null : pagina.cursor().codeer());

        return Response.ok(antwoord).build();
    }

    @Override
    public Response eventsBevestigen(BevestigingRequest bevestigingRequest) {
        UUID dvId = dvProvider.huidigeDvId();
        eisBinnenAanroeplimiet(dvId);

        Cursor cursor = ontleed(bevestigingRequest.getCursor());

        if (cursor == null) {
            throw Problems.badRequest("Cursor ontbreekt", "Een bevestiging heeft een cursor nodig");
        }

        try {
            eventfeed.bevestig(dvId, cursor);
        } catch (CursorVervallenException e) {
            throw cursorVervallen(dvId, e);
        }

        return Response.noContent().build();
    }

    // Het antwoord zegt niet waarom de cursor vervallen is: de reden noemt het cluster-epoch en de
    // grenzen van het log. Voor beheer staat hij in de log.
    private static RuntimeException cursorVervallen(UUID dvId, CursorVervallenException e) {
        Log.infof("Cursor van dienstverlener %s is vervallen: %s", dvId, e.getMessage());

        return Problems.gone(Problems.TYPE_CURSOR_VERVALLEN, "Cursor vervallen", CURSOR_VERVALLEN_DETAIL);
    }

    // Lezen en bevestigen tellen samen voor dezelfde limiet per dienstverlener.
    private void eisBinnenAanroeplimiet(UUID dvId) {
        if (!aanroeplimiet.registreer(dvId)) {
            throw Problems.tooManyRequests(Problems.TYPE_AANROEPLIMIET_OVERSCHREDEN, "Aanroeplimiet bereikt",
                    "Het maximum aantal feedaanroepen per minuut is bereikt; probeer het in de volgende minuut opnieuw",
                    aanroeplimiet.secondenTotVolgendVenster());
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
