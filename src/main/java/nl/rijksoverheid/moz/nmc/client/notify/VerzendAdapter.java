package nl.rijksoverheid.moz.nmc.client.notify;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import nl.rijksoverheid.moz.nmc.client.notify.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notify.generated.model.SendEmailRequest;
import nl.rijksoverheid.moz.nmc.client.notify.generated.model.SendEmailRequestPersonalisation;
import nl.rijksoverheid.moz.nmc.client.notify.generated.model.SendEmailResponse;
import nl.rijksoverheid.moz.nmc.helper.Problems;
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
        this.notifyApiKey = notifyApiKey.orElse("");
        this.notifyTemplateId = notifyTemplateId.orElse("");
    }

    public UUID verstuurEmail(String emailAdres, Map<String, String> berichtgegevens) {
        try {
            String authorization = notifyJwtFactory.authorizationHeader(notifyApiKey);
            notifyAuthorizationHolder.setBearerToken(authorization.substring(BEARER_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            Log.error("Ongeldige NotifyNL API-key geconfigureerd", e);
            throw Problems.serverError("Configuratiefout",
                    "Er kan momenteel geen notificatie worden verstuurd vanwege een configuratieprobleem");
        }

        SendEmailRequestPersonalisation personalisation = new SendEmailRequestPersonalisation();
        if (berichtgegevens != null) {
            personalisation.putAll(berichtgegevens);
        }

        SendEmailRequest notifyRequest = new SendEmailRequest()
                .emailAddress(emailAdres)
                .templateId(notifyTemplateId)
                .personalisation(personalisation);

        SendEmailResponse notifyResponse;
        try {
            notifyResponse = sendAMessageApi.sendEmail(notifyRequest);
        } catch (WebApplicationException e) {
            throw Problems.badGateway("NotifyNL fout",
                    "NotifyNL gaf status " + e.getResponse().getStatus() + " terug");
        }

        if (notifyResponse == null || notifyResponse.getId() == null) {
            throw Problems.badGateway("NotifyNL fout", "NotifyNL gaf geen notificatie-ID terug in de respons");
        }

        try {
            return UUID.fromString(notifyResponse.getId());
        } catch (IllegalArgumentException e) {
            throw Problems.badGateway("NotifyNL fout", "NotifyNL gaf een ongeldig notificatie-ID terug in de respons");
        }
    }
}
