package nl.rijksoverheid.moz.nmc.client.notifynl;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.GetMessageDataApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.GetMultipleMessagesResponse;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.GetOneMessageResponse;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailRequest;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailRequestPersonalisation;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailResponse;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class NotifyNLVerzendAdapter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final SendAMessageApi sendAMessageApi;
    private final GetMessageDataApi getMessageDataApi;
    private final NotifyNLJwtFactory notifyNLJwtFactory;
    private final NotifyNLAuthorizationHolder notifyNLAuthorizationHolder;
    private final String notifyApiKey;

    public NotifyNLVerzendAdapter(@RestClient SendAMessageApi sendAMessageApi,
                           @RestClient GetMessageDataApi getMessageDataApi,
                           NotifyNLJwtFactory notifyNLJwtFactory,
                           NotifyNLAuthorizationHolder notifyNLAuthorizationHolder,
                           @ConfigProperty(name = "notify.api-key") Optional<String> notifyApiKey) {
        this.sendAMessageApi = sendAMessageApi;
        this.getMessageDataApi = getMessageDataApi;
        this.notifyNLJwtFactory = notifyNLJwtFactory;
        this.notifyNLAuthorizationHolder = notifyNLAuthorizationHolder;
        this.notifyApiKey = notifyApiKey.filter(s -> !s.isBlank())
                .orElseThrow(() -> new IllegalStateException("notify.api-key is niet geconfigureerd"));
    }

    public UUID verstuurEmail(@NotNull String emailAdres, @NotNull String templateId, Map<String, String> berichtgegevens) throws NotifyNLConfiguratieException, NotifyNLVerzendException {
        return verstuurEmail(emailAdres, templateId, berichtgegevens, null);
    }

    /**
     * @param reference eigen referentie die NotifyNL in de receipts terugstuurt en waarop te zoeken is;
     *                  het NMC geeft het poging-id mee
     */
    public UUID verstuurEmail(@NotNull String emailAdres, @NotNull String templateId, Map<String, String> berichtgegevens,
                              String reference) throws NotifyNLConfiguratieException, NotifyNLVerzendException {
        autoriseer();
        SendEmailRequest notifyRequest = bouwVerzoek(emailAdres, templateId, berichtgegevens).reference(reference);
        SendEmailResponse notifyResponse = verstuur(notifyRequest);
        return extraheerNotificatieId(notifyResponse);
    }

    /**
     * Zoekt een eerdere verzending op de eigen referentie, voor een herclaim na een verlopen lease: is
     * de e-mail al aangeboden, dan hoeft dat niet nog eens.
     *
     * @return het NotifyNL-id van de gevonden verzending
     */
    public Optional<UUID> zoekOpReference(@NotNull String reference) throws NotifyNLConfiguratieException, NotifyNLVerzendException {
        autoriseer();

        try {
            GetMultipleMessagesResponse antwoord = getMessageDataApi.getMultipleMessagesStatus(null, null, reference, null, null);

            return antwoord == null || antwoord.getNotifications() == null
                    ? Optional.empty()
                    : antwoord.getNotifications().stream()
                            .filter(n -> reference.equals(n.getReference()) && n.getId() != null)
                            .map(n -> UUID.fromString(n.getId()))
                            .findFirst();
        } catch (WebApplicationException e) {
            throw new NotifyNLVerzendException("NotifyNL gaf status " + e.getResponse().getStatus() + " terug bij het zoeken op referentie", e);
        } catch (ProcessingException e) {
            throw new NotifyNLVerzendException("NotifyNL was niet bereikbaar bij het zoeken op referentie", e);
        } catch (IllegalArgumentException e) {
            throw new NotifyNLVerzendException("NotifyNL gaf een ongeldig notificatie-ID terug bij het zoeken op referentie", e);
        }
    }

    /**
     * Vraagt de afleverstatus van een verzending op, voor de navraag als de receipt uitblijft.
     *
     * @return leeg als NotifyNL de verzending niet (meer) kent (404)
     */
    public Optional<NotifyNLAfleverstatus> vraagStatusOp(@NotNull UUID notifyId) throws NotifyNLConfiguratieException, NotifyNLVerzendException {
        autoriseer();

        try {
            GetOneMessageResponse antwoord = getMessageDataApi.getMessageData(notifyId.toString());

            if (antwoord == null || antwoord.getStatus() == null) {
                throw new NotifyNLVerzendException("NotifyNL gaf geen status terug voor " + notifyId);
            }

            return Optional.of(new NotifyNLAfleverstatus(antwoord.getStatus(),
                    tijdstip(antwoord.getCompletedAt(), antwoord.getSentAt(), antwoord.getCreatedAt())));
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == 404) {
                return Optional.empty();
            }

            throw new NotifyNLVerzendException("NotifyNL gaf status " + e.getResponse().getStatus() + " terug bij de navraag", e);
        } catch (ProcessingException e) {
            throw new NotifyNLVerzendException("NotifyNL was niet bereikbaar bij de navraag", e);
        }
    }

    // Het eerste bruikbare tijdstip; een onleesbaar tijdstip telt als afwezig.
    private static OffsetDateTime tijdstip(String... kandidaten) {
        for (String kandidaat : kandidaten) {
            if (kandidaat == null || kandidaat.isBlank()) {
                continue;
            }

            try {
                return OffsetDateTime.parse(kandidaat);
            } catch (DateTimeParseException e) {
                Log.debugf("Onleesbaar tijdstip '%s' van NotifyNL genegeerd", kandidaat);
            }
        }

        return null;
    }

    private void autoriseer() throws NotifyNLConfiguratieException {
        try {
            String authorization = notifyNLJwtFactory.authorizationHeader(notifyApiKey);
            notifyNLAuthorizationHolder.setBearerToken(authorization.substring(BEARER_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            Log.error("Ongeldige NotifyNL API-key geconfigureerd", e);
            throw new NotifyNLConfiguratieException("Ongeldige NotifyNL API-key geconfigureerd", e);
        }
    }

    private SendEmailRequest bouwVerzoek(String emailAdres, String templateId, Map<String, String> berichtgegevens) {
        SendEmailRequestPersonalisation personalisation = new SendEmailRequestPersonalisation();
        if (berichtgegevens != null) {
            personalisation.putAll(berichtgegevens);
        }

        return new SendEmailRequest()
                .emailAddress(emailAdres)
                .templateId(templateId)
                .personalisation(personalisation);
    }

    private SendEmailResponse verstuur(SendEmailRequest notifyRequest) throws NotifyNLVerzendException {
        try {
            return sendAMessageApi.sendEmail(notifyRequest);
        } catch (WebApplicationException e) {
            throw new NotifyNLVerzendException("NotifyNL gaf status " + e.getResponse().getStatus() + " terug", e);
        } catch (ProcessingException e) {
            // Verbindingsfout of time-out: geen antwoord, dus ook geen status.
            throw new NotifyNLVerzendException("NotifyNL was niet bereikbaar", e);
        }
    }

    private UUID extraheerNotificatieId(SendEmailResponse notifyResponse) throws NotifyNLVerzendException {
        if (notifyResponse == null || notifyResponse.getId() == null) {
            throw new NotifyNLVerzendException("NotifyNL gaf geen notificatie-ID terug in de respons");
        }

        try {
            return UUID.fromString(notifyResponse.getId());
        } catch (IllegalArgumentException e) {
            throw new NotifyNLVerzendException("NotifyNL gaf een ongeldig notificatie-ID terug in de respons", e);
        }
    }
}
