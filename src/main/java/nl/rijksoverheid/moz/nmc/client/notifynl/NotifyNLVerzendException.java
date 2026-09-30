package nl.rijksoverheid.moz.nmc.client.notifynl;

import jakarta.ws.rs.WebApplicationException;

import java.util.Optional;

/**
 * NotifyNL zelf gaf een fout terug, was niet bereikbaar, of gaf een onbruikbare respons (geen of een
 * ongeldig notificatie-ID).
 */
public class NotifyNLVerzendException extends Exception {

    private final boolean adresAfgewezen;

    public NotifyNLVerzendException(String message) {
        super(message);
        this.adresAfgewezen = false;
    }

    public NotifyNLVerzendException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public NotifyNLVerzendException(String message, Throwable cause, boolean adresAfgewezen) {
        super(message, cause);
        this.adresAfgewezen = adresAfgewezen;
    }

    /** NotifyNL keurde het e-mailadres af; een uitkomst van de poging, geen storing. */
    public boolean adresAfgewezen() {
        return adresAfgewezen;
    }

    /** De HTTP-status van NotifyNL, als er een antwoord was; leeg bij een verbindingsfout of time-out. */
    public Optional<Integer> status() {
        return getCause() instanceof WebApplicationException e
                ? Optional.of(e.getResponse().getStatus())
                : Optional.empty();
    }
}
