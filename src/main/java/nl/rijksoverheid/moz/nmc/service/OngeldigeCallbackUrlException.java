package nl.rijksoverheid.moz.nmc.service;

/**
 * Thrown when a webhook URL fails validation.
 */
public class OngeldigeCallbackUrlException extends RuntimeException {

    public OngeldigeCallbackUrlException(String reden) {
        super("De webhook-URL is ongeldig: " + reden + ".");
    }
}
