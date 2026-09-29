package nl.rijksoverheid.moz.nmc.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * De termijnen waarop gegevens verdwijnen. Na de wistermijn wordt de sleutel per notificatie gewist,
 * waarna de rij pseudoniem is; na de bewaartermijn van het afleverbewijs verdwijnen de notificatie, haar
 * pogingen en de partities van het eventlog. Een cursor ouder dan de maximale cursorleeftijd houdt het
 * opruimen van het eventlog niet meer tegen; het register kan die leeftijd per dienstverlener afwijkend
 * vastleggen.
 */
@ApplicationScoped
public class Bewaartermijnen {

    private final Duration wistermijn;
    private final Duration afleverbewijs;
    private final Duration maxCursorleeftijd;

    public Bewaartermijnen(@ConfigProperty(name = "nmc.wissen.termijn") Duration wistermijn,
                           @ConfigProperty(name = "nmc.afleverbewijs.bewaartermijn") Duration afleverbewijs,
                           @ConfigProperty(name = "nmc.feed.max-cursorleeftijd") Duration maxCursorleeftijd) {
        if (!isPositief(wistermijn) || !isPositief(afleverbewijs) || !isPositief(maxCursorleeftijd)) {
            throw new IllegalStateException("nmc.wissen.termijn, nmc.afleverbewijs.bewaartermijn en "
                    + "nmc.feed.max-cursorleeftijd moeten positief zijn");
        }

        // De rij verdwijnt met de versleutelde gegevens; wissen moet dus eerder of tegelijk.
        if (afleverbewijs.compareTo(wistermijn) < 0) {
            throw new IllegalStateException("nmc.afleverbewijs.bewaartermijn (" + afleverbewijs
                    + ") mag niet korter zijn dan nmc.wissen.termijn (" + wistermijn + ")");
        }

        this.wistermijn = wistermijn;
        this.afleverbewijs = afleverbewijs;
        this.maxCursorleeftijd = maxCursorleeftijd;
    }

    /** Wanneer de sleutel van een notificatie die op {@code terminaalOp} terminaal werd, gewist wordt. */
    public OffsetDateTime wissenOp(OffsetDateTime terminaalOp) {
        return terminaalOp.plus(wistermijn);
    }

    /** Wat vóór dit tijdstip ligt, valt buiten de bewaartermijn van het afleverbewijs. */
    public OffsetDateTime bewarenVanaf(OffsetDateTime nu) {
        return nu.minus(afleverbewijs);
    }

    /** De maximale cursorleeftijd voor een dienstverlener zonder eigen afspraak in het register. */
    public Duration maxCursorleeftijd() {
        return maxCursorleeftijd;
    }

    private static boolean isPositief(Duration duur) {
        return !duur.isNegative() && !duur.isZero();
    }
}
