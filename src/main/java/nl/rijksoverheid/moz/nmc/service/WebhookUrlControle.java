package nl.rijksoverheid.moz.nmc.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Controleert de webhook-URL uit het register vóór elke levering met {@link CallbackUrlValidator} en
 * geeft hem genormaliseerd terug. Een bean, zodat een test een lokale http-ontvanger kan toelaten.
 */
@ApplicationScoped
public class WebhookUrlControle {

    /**
     * @throws OngeldigeCallbackUrlException als de URL niet door de validatie komt
     */
    public String controleer(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new OngeldigeCallbackUrlException("de URL is niet te ontleden");
        }

        CallbackUrlValidator.valideer(uri);

        return CallbackUrlValidator.normaliseer(uri);
    }
}
