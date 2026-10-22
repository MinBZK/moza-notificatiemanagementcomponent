package nl.rijksoverheid.moz.nmc.service;

/**
 * De versleutelde gegevens van een notificatie zijn niet te ontsleutelen: onbekende KEK-versie, een
 * sleutel die niet bij de KEK past, of gewijzigde ciphertext.
 */
public class OntsleutelenMisluktException extends RuntimeException {

    public OntsleutelenMisluktException(String message) {
        super(message);
    }

    public OntsleutelenMisluktException(String message, Throwable cause) {
        super(message, cause);
    }
}
