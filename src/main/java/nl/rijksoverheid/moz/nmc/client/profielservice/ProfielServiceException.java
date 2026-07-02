package nl.rijksoverheid.moz.nmc.client.profielservice;

/**
 * Profielservice gaf een onverwachte (niet-404) foutstatus terug.
 */
public class ProfielServiceException extends RuntimeException {

    public ProfielServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
