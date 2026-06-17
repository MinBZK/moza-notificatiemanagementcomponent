package nl.rijksoverheid.moz.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.api.model.AfleverstatusRequest;
import nl.rijksoverheid.moz.api.model.NotificatieAanvraagRequest;
import nl.rijksoverheid.moz.api.model.NotificatieResponse;
import nl.rijksoverheid.moz.client.consumentcallback.ConsumentCallbackAdapter;
import nl.rijksoverheid.moz.client.notify.NotifyClient;
import nl.rijksoverheid.moz.client.notify.NotifyEmailRequest;
import nl.rijksoverheid.moz.client.notify.NotifyEmailResponse;
import nl.rijksoverheid.moz.client.notify.NotifyJwtFactory;
import nl.rijksoverheid.moz.client.profielservice.ContactgegevenResponse;
import nl.rijksoverheid.moz.client.profielservice.PartijRequest;
import nl.rijksoverheid.moz.client.profielservice.PartijResponse;
import nl.rijksoverheid.moz.client.profielservice.ProfielServiceClient;
import nl.rijksoverheid.moz.common.ContactType;
import nl.rijksoverheid.moz.common.NotificatieStatus;
import nl.rijksoverheid.moz.domain.Notificatie;
import nl.rijksoverheid.moz.helper.Problems;
import nl.rijksoverheid.moz.repository.NotificatieRepository;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class NotificatieService {

    private final ProfielServiceClient profielServiceClient;
    private final NotifyClient notifyClient;
    private final NotifyJwtFactory notifyJwtFactory;
    private final NotificatieRepository notificatieRepository;
    private final ConsumentCallbackAdapter consumentCallbackAdapter;
    private final String notifyApiKey;
    private final String notifyTemplateId;

    public NotificatieService(@RestClient ProfielServiceClient profielServiceClient,
                               @RestClient NotifyClient notifyClient,
                               NotifyJwtFactory notifyJwtFactory,
                               NotificatieRepository notificatieRepository,
                               ConsumentCallbackAdapter consumentCallbackAdapter,
                               @ConfigProperty(name = "notify.api-key") Optional<String> notifyApiKey,
                               @ConfigProperty(name = "notify.template-id") Optional<String> notifyTemplateId) {
        this.profielServiceClient = profielServiceClient;
        this.notifyClient = notifyClient;
        this.notifyJwtFactory = notifyJwtFactory;
        this.notificatieRepository = notificatieRepository;
        this.consumentCallbackAdapter = consumentCallbackAdapter;
        this.notifyApiKey = notifyApiKey.orElse("");
        this.notifyTemplateId = notifyTemplateId.orElse("");
    }

    @Transactional
    public NotificatieResponse versturen(NotificatieAanvraagRequest request) {
        PartijResponse partij = zoekPartij(request);
        String emailAdres = zoekEmailAdres(partij, request.getDienstverlener(), request.getDienst());

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

        // Persist before calling NotifyNL so that a DB failure never results in a sent email with no record.
        Notificatie notificatie = new Notificatie();
        notificatie.callbackUrl = request.getCallbackUrl() != null ? request.getCallbackUrl().toString() : null;
        notificatie.status = NotificatieStatus.CREATED;
        notificatie.aangemaakt = OffsetDateTime.now(ZoneOffset.UTC);
        notificatieRepository.persist(notificatie);

        Response notifyResponse = notifyClient.verstuurEmail(authorization, notifyRequest);
        try {
            if (notifyResponse.getStatus() != Response.Status.CREATED.getStatusCode()) {
                throw Problems.badGateway("NotifyNL fout",
                        "NotifyNL gaf status " + notifyResponse.getStatus() + " terug");
            }

            NotifyEmailResponse notifyNlResponse = notifyResponse.readEntity(NotifyEmailResponse.class);
            if (notifyNlResponse == null || notifyNlResponse.id == null) {
                throw Problems.badGateway("NotifyNL fout", "NotifyNL gaf geen notificatie-ID terug in de respons");
            }

            notificatie.notifyNlNotificatieId = notifyNlResponse.id;
            notificatie.status = NotificatieStatus.SENDING;

            return new NotificatieResponse().notificatieId(notificatie.id);
        } finally {
            notifyResponse.close();
        }
    }

    @Transactional
    public void verwerkAfleverstatus(AfleverstatusRequest request) {
        UUID notifyNlNotificatieId = request.getId();

        Notificatie notificatie = notificatieRepository
                .findByNotifyNlNotificatieId(notifyNlNotificatieId)
                .orElseThrow(() -> Problems.notFound("Notificatie niet gevonden",
                        "Geen notificatie gevonden voor NotifyNL-referentie " + notifyNlNotificatieId));

        notificatie.status = parseStatus(request.getStatus());
        consumentCallbackAdapter.stuurStatusUpdate(notificatie);
    }

    private NotificatieStatus parseStatus(String notifyStatus) {
        try {
            return NotificatieStatus.valueOf(notifyStatus.replace("-", "_").toUpperCase());
        } catch (IllegalArgumentException e) {
            Log.warnf("Onbekende NotifyNL-status ontvangen: %s — opgeslagen als technische fout", notifyStatus);
            return NotificatieStatus.TECHNICAL_FAILURE;
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
                        "Geen partij gevonden voor het opgegeven identificerend nummer");
            }

            Log.error("Profielservice gaf status " + e.getResponse().getStatus() + " terug", e);
            throw Problems.serverError("Profielservice fout",
                    "Er is een fout opgetreden bij het ophalen van de contactgegevens");
        }
    }

    private String zoekEmailAdres(PartijResponse partij, String dienstverlener, String dienst) {
        List<ContactgegevenResponse> emails = partij.contactgegevens == null
                ? List.of()
                : partij.contactgegevens.stream()
                        .filter(c -> c.type == ContactType.Email)
                        .toList();

        return emails.stream().filter(c -> heeftExacteScope(c, dienstverlener, dienst)).findFirst()
                .or(() -> emails.stream().filter(c -> heeftDienstverlenerScope(c, dienstverlener)).findFirst())
                .or(() -> emails.stream().filter(c -> c.isDefault).findFirst())
                .or(() -> emails.stream().filter(c -> c.scopes == null || c.scopes.isEmpty()).findFirst())
                .map(c -> c.waarde)
                .orElseThrow(() -> Problems.notFound("Geen e-mailadres gevonden",
                        "Geen e-mailadres gevonden voor het opgegeven identificerend nummer"));
    }

    private boolean heeftExacteScope(ContactgegevenResponse email, String dienstverlener, String dienst) {
        return email.scopes != null && email.scopes.stream()
                .anyMatch(s -> dienstverlener.equals(s.dienstverlenerNaam) && dienst.equals(s.dienstNaam));
    }

    private boolean heeftDienstverlenerScope(ContactgegevenResponse email, String dienstverlener) {
        return email.scopes != null && email.scopes.stream()
                .anyMatch(s -> dienstverlener.equals(s.dienstverlenerNaam) && (s.dienstNaam == null || s.dienstNaam.isBlank()));
    }
}
