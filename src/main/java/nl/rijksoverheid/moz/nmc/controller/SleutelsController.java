package nl.rijksoverheid.moz.nmc.controller;

import nl.rijksoverheid.moz.nmc.api.SleutelsApi;
import nl.rijksoverheid.moz.nmc.api.model.JsonWebKey;
import nl.rijksoverheid.moz.nmc.api.model.JsonWebKeySet;
import nl.rijksoverheid.moz.nmc.service.WebhookSleutel;

import java.util.List;

/** Publiceert de publieke helft van de webhook-sleutel als JWKS, zonder authenticatie. */
public class SleutelsController implements SleutelsApi {

    private final WebhookSleutel webhookSleutel;

    public SleutelsController(WebhookSleutel webhookSleutel) {
        this.webhookSleutel = webhookSleutel;
    }

    @Override
    public JsonWebKeySet sleutelsOpvragen() {
        return new JsonWebKeySet(List.of(new JsonWebKey(JsonWebKey.KtyEnum.RSA, JsonWebKey.UseEnum.SIG,
                JsonWebKey.AlgEnum.RS256, webhookSleutel.keyId(), webhookSleutel.modulus(),
                webhookSleutel.publiekeExponent())));
    }
}
