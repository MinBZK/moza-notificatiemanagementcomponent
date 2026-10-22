package nl.rijksoverheid.moz.nmc.client.notifynl;

import jakarta.ws.rs.WebApplicationException;

import java.util.Optional;

/**
 * NotifyNL zelf gaf een fout terug, was niet bereikbaar, of gaf een onbruikbare respons (geen of een
 * ongeldig notificatie-ID).
 */
public class NotifyNLVerzendException extends Exception {

    private final boolean adresAfgewezen;
    private final boolean onbruikbaarAntwoord;

    public NotifyNLVerzendException(String message) {
        this(message, null, false, false);
    }

    public NotifyNLVerzendException(String message, Throwable cause) {
        this(message, cause, false, false);
    }

    public NotifyNLVerzendException(String message, Throwable cause, boolean adresAfgewezen) {
        this(message, cause, adresAfgewezen, false);
    }

    private NotifyNLVerzendException(String message, Throwable cause, boolean adresAfgewezen, boolean onbruikbaarAntwoord) {
        super(message, cause);
        this.adresAfgewezen = adresAfgewezen;
        this.onbruikbaarAntwoord = onbruikbaarAntwoord;
    }

    /** NotifyNL antwoordde, maar zonder bruikbaar notificatie-ID; geen storing, want het herhaalt zich. */
    public static NotifyNLVerzendException onbruikbaarAntwoord(String message, Throwable cause) {
        return new NotifyNLVerzendException(message, cause, false, true);
    }

    /** NotifyNL keurde het e-mailadres af; een uitkomst van de poging, geen storing. */
    public boolean adresAfgewezen() {
        return adresAfgewezen;
    }

    /** NotifyNL gaf een antwoord zonder bruikbaar notificatie-ID. */
    public boolean onbruikbaarAntwoord() {
        return onbruikbaarAntwoord;
    }

    /** De HTTP-status van NotifyNL, als er een antwoord was; leeg bij een verbindingsfout of time-out. */
    public Optional<Integer> status() {
        return getCause() instanceof WebApplicationException e
                ? Optional.of(e.getResponse().getStatus())
                : Optional.empty();
    }
}
