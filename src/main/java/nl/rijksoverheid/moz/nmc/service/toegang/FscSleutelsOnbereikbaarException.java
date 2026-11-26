package nl.rijksoverheid.moz.nmc.service.toegang;

/** De JWKS van de Manager is niet op te halen en er zijn geen bruikbare sleutels bewaard. */
public class FscSleutelsOnbereikbaarException extends RuntimeException {

    public FscSleutelsOnbereikbaarException(String message) {
        super(message);
    }

    public FscSleutelsOnbereikbaarException(String message, Throwable cause) {
        super(message, cause);
    }
}
