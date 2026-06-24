package nl.rijksoverheid.moz.nmc.client.notify;

// De geconfigureerde NotifyNL API-key kan niet gebruikt worden om een geldig JWT op te bouwen.
public class NotifyConfiguratieException extends RuntimeException {

    public NotifyConfiguratieException(String message, Throwable cause) {
        super(message, cause);
    }
}
