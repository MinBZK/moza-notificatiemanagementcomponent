package nl.rijksoverheid.moz.nmc.client.notifynl;

// NotifyNL zelf gaf een fout terug, of een onbruikbare respons (geen of een ongeldig notificatie-ID).
public class NotifyNLVerzendException extends RuntimeException {

    public NotifyNLVerzendException(String message) {
        super(message);
    }

    public NotifyNLVerzendException(String message, Throwable cause) {
        super(message, cause);
    }
}
