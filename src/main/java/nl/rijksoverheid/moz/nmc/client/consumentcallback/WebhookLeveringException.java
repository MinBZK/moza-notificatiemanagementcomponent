package nl.rijksoverheid.moz.nmc.client.consumentcallback;

/** De webhook van de Dienstverlener nam de levering niet aan: geen 2xx, geen verbinding of niet op tijd. */
public class WebhookLeveringException extends RuntimeException {

    public WebhookLeveringException(String bericht) {
        super(bericht);
    }

    public WebhookLeveringException(String bericht, Throwable oorzaak) {
        super(bericht, oorzaak);
    }
}
