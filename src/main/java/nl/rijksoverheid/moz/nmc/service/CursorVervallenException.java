package nl.rijksoverheid.moz.nmc.service;

/**
 * De cursor komt uit een ander cluster-epoch of wijst onder het oudste beschikbare event; de
 * Dienstverlener leest opnieuw zonder cursor.
 */
public class CursorVervallenException extends RuntimeException {

    public CursorVervallenException(String message) {
        super(message);
    }
}
