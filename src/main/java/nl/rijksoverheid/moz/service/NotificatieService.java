package nl.rijksoverheid.moz.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.client.notify.NotifyClient;
import nl.rijksoverheid.moz.client.notify.NotifyEmailRequest;
import nl.rijksoverheid.moz.client.notify.NotifyEmailResponse;
import nl.rijksoverheid.moz.client.notify.NotifyJwtFactory;
import nl.rijksoverheid.moz.client.profielservice.ContactgegevenResponse;
import nl.rijksoverheid.moz.client.profielservice.PartijRequest;
import nl.rijksoverheid.moz.client.profielservice.PartijResponse;
import nl.rijksoverheid.moz.client.profielservice.ProfielServiceClient;
import nl.rijksoverheid.moz.api.model.NotificatieAanvraagRequest;
import nl.rijksoverheid.moz.api.model.NotificatieResponse;
import nl.rijksoverheid.moz.common.ContactType;
import nl.rijksoverheid.moz.helper.Problems;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class NotificatieService {

    private final ProfielServiceClient profielServiceClient;
    private final NotifyClient notifyClient;
    private final NotifyJwtFactory notifyJwtFactory;
    private final String notifyApiKey;
    private final String notifyTemplateId;

    public NotificatieService(@RestClient ProfielServiceClient profielServiceClient,
                               @RestClient NotifyClient notifyClient,
                               NotifyJwtFactory notifyJwtFactory,
                               @ConfigProperty(name = "notify.api-key") Optional<String> notifyApiKey,
                               @ConfigProperty(name = "notify.template-id") Optional<String> notifyTemplateId) {
        this.profielServiceClient = profielServiceClient;
        this.notifyClient = notifyClient;
        this.notifyJwtFactory = notifyJwtFactory;
        this.notifyApiKey = notifyApiKey.orElse("");
        this.notifyTemplateId = notifyTemplateId.orElse("");
    }

    public NotificatieResponse versturen(NotificatieAanvraagRequest request) {
        PartijResponse partij = zoekPartij(request);
        String emailAdres = zoekEmailAdres(partij, request.getIdentificatieNummer());

        NotifyEmailRequest notifyRequest = new NotifyEmailRequest(
                emailAdres,
                notifyTemplateId,
                request.getBerichtgegevens() != null ? request.getBerichtgegevens() : Map.of());

        String authorization;
        try {
            authorization = notifyJwtFactory.authorizationHeader(notifyApiKey);
        } catch (IllegalArgumentException e) {
            Log.error("Ongeldige NotifyNL API-key geconfigureerd", e);
            throw Problems.serverError("Configuratiefout",
                    "Er kan momenteel geen notificatie worden verstuurd vanwege een configuratieprobleem");
        }

        Response notifyResponse = notifyClient.verstuurEmail(authorization, notifyRequest);
        try {
            if (notifyResponse.getStatus() != Response.Status.OK.getStatusCode()) {
                throw Problems.badGateway("NotifyNL fout",
                        "NotifyNL gaf status " + notifyResponse.getStatus() + " terug");
            }

            UUID notifyReferentie = notifyResponse.readEntity(NotifyEmailResponse.class).id;

            return new NotificatieResponse().notifyReferentie(notifyReferentie);
        } finally {
            notifyResponse.close();
        }
    }

    private PartijResponse zoekPartij(NotificatieAanvraagRequest request) {
        PartijRequest partijRequest = new PartijRequest();
        partijRequest.identificatieType = request.getIdentificatieType();
        partijRequest.identificatieNummer = request.getIdentificatieNummer();
        partijRequest.dienstverlener = request.getDienstverlener();
        partijRequest.dienstNaam = request.getDienst();

        try {
            return profielServiceClient.zoekPartij(partijRequest);
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
                throw Problems.notFound("Partij niet gevonden",
                        "Geen partij gevonden voor " + request.getIdentificatieType() + " " + request.getIdentificatieNummer());
            }

            Log.error("Profielservice gaf status " + e.getResponse().getStatus() + " terug", e);
            throw Problems.serverError("Profielservice fout",
                    "Er is een fout opgetreden bij het ophalen van de contactgegevens");
        }
    }

    private String zoekEmailAdres(PartijResponse partij, String identificatieNummer) {
        List<ContactgegevenResponse> emailAdressen = partij.contactgegevens == null
                ? List.of()
                : partij.contactgegevens.stream()
                        .filter(contactgegeven -> contactgegeven.type == ContactType.Email)
                        .toList();

        return emailAdressen.stream()
                .filter(contactgegeven -> contactgegeven.isDefault)
                .findFirst()
                .or(() -> emailAdressen.stream().findFirst())
                .map(contactgegeven -> contactgegeven.waarde)
                .orElseThrow(() -> Problems.notFound("Geen e-mailadres gevonden",
                        "Geen e-mailadres gevonden voor " + identificatieNummer));
    }
}
