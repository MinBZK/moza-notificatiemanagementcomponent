package nl.rijksoverheid.moz.nmc.domain;

/** De meegegeven cursor is geen door de NMC uitgegeven cursor. */
public class OngeldigeCursorException extends RuntimeException {

    public OngeldigeCursorException(String message) {
        super(message);
    }
}
