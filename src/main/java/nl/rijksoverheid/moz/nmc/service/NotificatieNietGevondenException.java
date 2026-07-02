package nl.rijksoverheid.moz.nmc.service;

/**
 * Geen Notificatie gevonden voor de opgegeven NotifyNL-referentie.
 */
public class NotificatieNietGevondenException extends RuntimeException {

    public NotificatieNietGevondenException(String message) {
        super(message);
    }
}
