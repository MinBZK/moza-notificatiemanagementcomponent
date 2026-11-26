package nl.rijksoverheid.moz.nmc.controller;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.api.SleutelsApi;
import nl.rijksoverheid.moz.nmc.api.model.JsonWebKey;
import nl.rijksoverheid.moz.nmc.api.model.JsonWebKeySet;
import nl.rijksoverheid.moz.nmc.config.WebhookConfig;
import nl.rijksoverheid.moz.nmc.service.webhook.WebhookSleutel;

import java.util.List;

/**
 * Publiceert de publieke helft van de webhook-sleutel als JWKS, zonder authenticatie. Het antwoord
 * zegt met {@code Cache-Control} hoe lang een ontvanger het mag bewaren.
 */
@BuitenFsc
public class SleutelsController implements SleutelsApi {

    private final WebhookSleutel webhookSleutel;
    private final String cacheControl;

    public SleutelsController(WebhookSleutel webhookSleutel, WebhookConfig config) {
        this.webhookSleutel = webhookSleutel;
        this.cacheControl = "public, max-age=" + config.jwt().jwksMaxAge().toSeconds();
    }

    @Override
    public Response sleutelsOpvragen() {
        List<JsonWebKey> sleutels = webhookSleutel.publiekeSleutels().stream()
                .map(sleutel -> new JsonWebKey(JsonWebKey.KtyEnum.RSA, JsonWebKey.UseEnum.SIG, JsonWebKey.AlgEnum.RS256,
                        sleutel.keyId(), sleutel.modulus(), sleutel.exponent()))
                .toList();

        return Response.ok(new JsonWebKeySet(sleutels)).header(HttpHeaders.CACHE_CONTROL, cacheControl).build();
    }
}
