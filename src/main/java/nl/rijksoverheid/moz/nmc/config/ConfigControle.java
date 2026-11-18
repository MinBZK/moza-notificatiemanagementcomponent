package nl.rijksoverheid.moz.nmc.config;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Controleert bij het opstarten de regels die over meer dan één mapping gaan. Een geschonden regel
 * laat de applicatie niet starten.
 */
@Startup
@ApplicationScoped
public class ConfigControle {

    public ConfigControle(TaakConfig taak, WebhookConfig webhook) {
        // Een aanroep die langer duurt dan de lease, laat een tweede worker dezelfde bundel nog eens afleveren.
        if (webhook.timeout().compareTo(taak.lease()) >= 0) {
            throw new IllegalStateException("nmc.webhook.timeout moet korter dan nmc.taak.lease ("
                    + taak.lease() + ") zijn, is " + webhook.timeout());
        }
    }
}
