package nl.rijksoverheid.moz.nmc.client.consumentcallback;

/** De webhook van de Dienstverlener nam de levering niet aan: geen 2xx, of geen verbinding. */
public class WebhookLeveringException extends RuntimeException {

    public WebhookLeveringException(String bericht, Throwable oorzaak) {
        super(bericht, oorzaak);
    }
}
