package nl.rijksoverheid.moz.nmc.client.notify;

// NotifyNL zelf gaf een fout terug, of een onbruikbare respons (geen of een ongeldig notificatie-ID).
public class NotifyVerzendException extends RuntimeException {

    public NotifyVerzendException(String message) {
        super(message);
    }
}
