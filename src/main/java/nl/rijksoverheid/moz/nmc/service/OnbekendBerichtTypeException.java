package nl.rijksoverheid.moz.nmc.service;

/**
 * Onbekend berichttype opgegeven bij de notificatie-aanvraag.
 */
public class OnbekendBerichtTypeException extends RuntimeException {

    public OnbekendBerichtTypeException(String berichtType) {
        super("Het berichttype '" + berichtType + "' is niet bekend.");
    }
}
