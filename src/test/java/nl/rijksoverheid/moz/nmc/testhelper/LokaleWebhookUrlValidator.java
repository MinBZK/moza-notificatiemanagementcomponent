package nl.rijksoverheid.moz.nmc.testhelper;

import io.quarkus.test.Mock;
import nl.rijksoverheid.moz.nmc.service.WebhookUrlValidator;

/**
 * Laat in tests de http-URL van {@link WebhookOntvanger} op localhost toe; elke andere URL gaat door
 * de echte controle. Zonder deze uitzondering is een levering alleen tegen een publieke https-host
 * te testen.
 */
@Mock
public class LokaleWebhookUrlValidator extends WebhookUrlValidator {

    @Override
    public String controleer(String url) {
        if (url.startsWith("http://localhost:") && url.contains("/test/webhook/")) {
            return url;
        }

        return super.controleer(url);
    }
}
