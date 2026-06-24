package nl.rijksoverheid.moz.nmc.client.notify;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.WebApplicationException;
import nl.rijksoverheid.moz.nmc.client.notify.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notify.generated.model.SendEmailRequest;
import nl.rijksoverheid.moz.nmc.client.notify.generated.model.SendEmailRequestPersonalisation;
import nl.rijksoverheid.moz.nmc.client.notify.generated.model.SendEmailResponse;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class VerzendAdapter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final SendAMessageApi sendAMessageApi;
    private final NotifyJwtFactory notifyJwtFactory;
    private final NotifyAuthorizationHolder notifyAuthorizationHolder;
    private final String notifyApiKey;
    private final String notifyTemplateId;

    public VerzendAdapter(@RestClient SendAMessageApi sendAMessageApi,
                           NotifyJwtFactory notifyJwtFactory,
                           NotifyAuthorizationHolder notifyAuthorizationHolder,
                           @ConfigProperty(name = "notify.api-key") Optional<String> notifyApiKey,
                           @ConfigProperty(name = "notify.template-id") Optional<String> notifyTemplateId) {
        this.sendAMessageApi = sendAMessageApi;
        this.notifyJwtFactory = notifyJwtFactory;
        this.notifyAuthorizationHolder = notifyAuthorizationHolder;
        this.notifyApiKey = notifyApiKey.filter(s -> !s.isBlank())
                .orElseThrow(() -> new IllegalStateException("notify.api-key is niet geconfigureerd"));
        this.notifyTemplateId = notifyTemplateId.filter(s -> !s.isBlank())
                .orElseThrow(() -> new IllegalStateException("notify.template-id is niet geconfigureerd"));
    }

    public UUID verstuurEmail(@NotNull String emailAdres, Map<String, String> berichtgegevens) {
        autoriseer();
        SendEmailRequest notifyRequest = bouwVerzoek(emailAdres, berichtgegevens);
        SendEmailResponse notifyResponse = verstuur(notifyRequest);
        return extraheerNotificatieId(notifyResponse);
    }

    private void autoriseer() {
        try {
            String authorization = notifyJwtFactory.authorizationHeader(notifyApiKey);
            notifyAuthorizationHolder.setBearerToken(authorization.substring(BEARER_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            Log.error("Ongeldige NotifyNL API-key geconfigureerd", e);
            throw new NotifyConfiguratieException("Ongeldige NotifyNL API-key geconfigureerd", e);
        }
    }

    private SendEmailRequest bouwVerzoek(String emailAdres, Map<String, String> berichtgegevens) {
        SendEmailRequestPersonalisation personalisation = new SendEmailRequestPersonalisation();
        if (berichtgegevens != null) {
            personalisation.putAll(berichtgegevens);
        }

        return new SendEmailRequest()
                .emailAddress(emailAdres)
                .templateId(notifyTemplateId)
                .personalisation(personalisation);
    }

    private SendEmailResponse verstuur(SendEmailRequest notifyRequest) {
        try {
            return sendAMessageApi.sendEmail(notifyRequest);
        } catch (WebApplicationException e) {
            throw new NotifyVerzendException("NotifyNL gaf status " + e.getResponse().getStatus() + " terug");
        }
    }

    private UUID extraheerNotificatieId(SendEmailResponse notifyResponse) {
        if (notifyResponse == null || notifyResponse.getId() == null) {
            throw new NotifyVerzendException("NotifyNL gaf geen notificatie-ID terug in de respons");
        }

        try {
            return UUID.fromString(notifyResponse.getId());
        } catch (IllegalArgumentException e) {
            throw new NotifyVerzendException("NotifyNL gaf een ongeldig notificatie-ID terug in de respons");
        }
    }
}
