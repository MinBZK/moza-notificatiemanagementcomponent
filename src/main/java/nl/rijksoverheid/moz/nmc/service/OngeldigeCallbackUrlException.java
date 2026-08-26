package nl.rijksoverheid.moz.nmc.service;

/**
 * Thrown when the callbackUrl in a notificatie-aanvraag fails validation.
 */
public class OngeldigeCallbackUrlException extends RuntimeException {

    public OngeldigeCallbackUrlException(String reden) {
        super("De callbackUrl is ongeldig: " + reden + ".");
    }
}
