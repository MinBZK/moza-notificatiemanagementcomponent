package nl.rijksoverheid.moz.nmc.service.toegang;

/** Het FSC-toegangstoken is niet te ontleden, niet geldig ondertekend of voldoet niet aan de verwachte claims. */
public class OngeldigFscTokenException extends RuntimeException {

    public OngeldigFscTokenException(String message) {
        super(message);
    }
}
