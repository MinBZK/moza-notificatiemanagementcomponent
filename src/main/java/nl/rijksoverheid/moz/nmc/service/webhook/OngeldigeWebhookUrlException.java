package nl.rijksoverheid.moz.nmc.service.webhook;

/** Een webhook-URL die niet door {@link WebhookUrlValidator} komt. */
public class OngeldigeWebhookUrlException extends RuntimeException {

    public OngeldigeWebhookUrlException(String reden) {
        super("De webhook-URL is ongeldig: " + reden + ".");
    }
}
