package nl.rijksoverheid.moz.nmc.client.notifynl;

// De geconfigureerde NotifyNL API-key kan niet gebruikt worden om een geldig JWT op te bouwen.
public class NotifyNLConfiguratieException extends Exception {

    public NotifyNLConfiguratieException(String message, Throwable cause) {
        super(message, cause);
    }
}
