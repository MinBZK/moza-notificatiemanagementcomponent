package nl.rijksoverheid.moz.nmc.client.notifynl;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailRequest;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailRequestPersonalisation;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailResponse;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class NotifyNLVerzendAdapter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final SendAMessageApi sendAMessageApi;
    private final NotifyNLJwtFactory notifyNLJwtFactory;
    private final NotifyNLAuthorizationHolder notifyNLAuthorizationHolder;
    private final String notifyApiKey;

    public NotifyNLVerzendAdapter(@RestClient SendAMessageApi sendAMessageApi,
                           NotifyNLJwtFactory notifyNLJwtFactory,
                           NotifyNLAuthorizationHolder notifyNLAuthorizationHolder,
                           @ConfigProperty(name = "notify.api-key") Optional<String> notifyApiKey) {
        this.sendAMessageApi = sendAMessageApi;
        this.notifyNLJwtFactory = notifyNLJwtFactory;
        this.notifyNLAuthorizationHolder = notifyNLAuthorizationHolder;
        this.notifyApiKey = notifyApiKey.filter(s -> !s.isBlank())
                .orElseThrow(() -> new IllegalStateException("notify.api-key is niet geconfigureerd"));
    }

    public UUID verstuurEmail(@NotNull String emailAdres, @NotNull String templateId, Map<String, String> berichtgegevens) throws NotifyNLConfiguratieException, NotifyNLVerzendException {
        autoriseer();
        SendEmailRequest notifyRequest = bouwVerzoek(emailAdres, templateId, berichtgegevens);
        SendEmailResponse notifyResponse = verstuur(notifyRequest);
        return extraheerNotificatieId(notifyResponse);
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
            throw new NotifyNLVerzendException("NotifyNL gaf status " + e.getResponse().getStatus()
                    + " terug: " + foutmelding(e.getResponse()), e);
        }
    }

    // NotifyNL zet de reden van een afwijzing in de body. Zonder die reden is een 400 niet te herleiden.
    private static String foutmelding(Response respons) {
        try {
            String body = respons.readEntity(String.class);
            return body == null || body.isBlank() ? "geen body" : body;
        } catch (RuntimeException e) {
            return "body niet leesbaar";
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
