package nl.rijksoverheid.moz.nmc.client.profielservice;

// De partij is gevonden, maar geen van de contactgegevens leverde een bruikbaar e-mailadres op.
public class GeenEmailadresGevondenException extends RuntimeException {

    public GeenEmailadresGevondenException(String message) {
        super(message);
    }
}
