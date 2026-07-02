package nl.rijksoverheid.moz.nmc.client.profielservice;

/**
 * Profielservice gaf een 404 terug voor de opgegeven partij-identificatie.
 */
public class PartijNietGevondenException extends RuntimeException {

    public PartijNietGevondenException(String message) {
        super(message);
    }
}
