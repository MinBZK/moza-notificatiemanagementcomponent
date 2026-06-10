package nl.rijksoverheid.moz.controller;

import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.dto.request.ContactherstelAanvraagRequest;
import nl.rijksoverheid.moz.dto.request.DeliveryReceiptRequest;
import nl.rijksoverheid.moz.dto.request.NotificatieAanvraagRequest;
import nl.rijksoverheid.moz.dto.response.NotificatieResponse;
import nl.rijksoverheid.moz.dto.response.VerzendingResponse;
import nl.rijksoverheid.moz.entity.Notificatie;
import nl.rijksoverheid.moz.entity.Verzending;
import nl.rijksoverheid.moz.mapper.NotificatieMapper;
import nl.rijksoverheid.moz.service.NotificatieService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.net.URI;
import java.util.UUID;

@Path("/api/nmc/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Notificaties", description = "Endpoints voor het versturen en opvolgen van notificaties")
public class NotificatieController {

    private final NotificatieService notificatieService;
    private final NotificatieMapper notificatieMapper;

    public NotificatieController(NotificatieService notificatieService, NotificatieMapper notificatieMapper) {
        this.notificatieService = notificatieService;
        this.notificatieMapper = notificatieMapper;
    }

    @POST
    @Path("/notificaties")
    @Operation(
            summary = "Notificatie aanmaken",
            description = "Maakt een nieuwe notificatie aan en start de eerste verzending"
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "201",
                    description = "Notificatie succesvol aangemaakt",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = NotificatieResponse.class))
            ),
            @APIResponse(responseCode = "400", description = "Ongeldige aanvraag")
    })
    public Response notificatieAanmaken(@Valid NotificatieAanvraagRequest request) {
        Notificatie notificatie = notificatieService.aanmaken(request);
        NotificatieResponse response = notificatieMapper.naarResponse(notificatie);
        URI uri = URI.create("/api/nmc/v1/notificaties/" + notificatie.id);

        return Response.created(uri).entity(response).build();
    }

    @GET
    @Path("/notificaties/{id}")
    @Operation(
            summary = "Status van een notificatie opvragen",
            description = "Haalt een notificatie op, inclusief de status van alle verzendingen"
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "Notificatie gevonden",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = NotificatieResponse.class))
            ),
            @APIResponse(responseCode = "404", description = "Notificatie niet gevonden")
    })
    public Response notificatieOphalen(@PathParam("id") UUID id) {
        Notificatie notificatie = notificatieService.ophalen(id);

        return Response.ok(notificatieMapper.naarResponse(notificatie)).build();
    }

    @POST
    @Path("/notificaties/{id}/contactherstel")
    @Operation(
            summary = "Contactherstel initiëren",
            description = "Start een nieuwe verzendpoging (contactherstel) voor een bestaande notificatie"
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "201",
                    description = "Contactherstel succesvol geïnitieerd",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = VerzendingResponse.class))
            ),
            @APIResponse(responseCode = "400", description = "Ongeldige aanvraag"),
            @APIResponse(responseCode = "404", description = "Notificatie niet gevonden")
    })
    public Response contactherstelInitieren(@PathParam("id") UUID id, @Valid ContactherstelAanvraagRequest request) {
        Verzending verzending = notificatieService.contactherstelInitieren(id, request);
        VerzendingResponse response = notificatieMapper.naarVerzendingResponse(verzending);
        URI uri = URI.create("/api/nmc/v1/notificaties/" + id);

        return Response.created(uri).entity(response).build();
    }

    @POST
    @Path("/notify-callback")
    @Operation(
            summary = "Notify-callback verwerken",
            description = "Verwerkt een delivery receipt van NotifyNL en werkt de status van de bijbehorende verzending bij"
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Delivery receipt verwerkt"),
            @APIResponse(responseCode = "404", description = "Geen verzending gevonden voor de meegegeven Notify-referentie")
    })
    public Response notifyCallback(@Valid DeliveryReceiptRequest request) {
        notificatieService.verwerkDeliveryReceipt(request);

        return Response.ok().build();
    }
}
