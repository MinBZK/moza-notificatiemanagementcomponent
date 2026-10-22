package nl.rijksoverheid.moz.nmc.client.profielservice;

import jakarta.ws.rs.WebApplicationException;

import java.util.Optional;

/**
 * De Profielservice gaf een foutstatus terug die niet over de partij gaat, of was niet bereikbaar.
 */
public class ProfielServiceException extends RuntimeException {

    public ProfielServiceException(String message, Throwable cause) {
        super(message, cause);
    }

    /** De HTTP-status van de Profielservice, als er een antwoord was. */
    public Optional<Integer> status() {
        return getCause() instanceof WebApplicationException e
                ? Optional.of(e.getResponse().getStatus())
                : Optional.empty();
    }
}
