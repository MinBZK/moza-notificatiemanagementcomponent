package nl.rijksoverheid.moz.nmc.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.OffsetDateTime;
import nl.rijksoverheid.moz.nmc.config.AfleverbewijsConfig;
import nl.rijksoverheid.moz.nmc.config.FeedConfig;
import nl.rijksoverheid.moz.nmc.config.WissenConfig;

/**
 * De termijnen waarop het onderhoud gegevens opruimt. Na de wistermijn wordt de sleutel per notificatie gewist,
 * waarna de rij pseudoniem is; na de bewaartermijn van het afleverbewijs verdwijnen de notificatie, haar
 * pogingen en de partities van het eventlog. Een cursor ouder dan de maximale cursorleeftijd houdt het
 * opruimen van het eventlog niet meer tegen; het register kan die leeftijd per dienstverlener afwijkend
 * vastleggen.
 */
@ApplicationScoped
public class Opruimtermijnen {

    private final Duration wistermijn;
    private final Duration afleverbewijstermijn;
    private final Duration maxCursorleeftijd;

    public Opruimtermijnen(WissenConfig wissenConfig, AfleverbewijsConfig afleverbewijsConfig, FeedConfig feedConfig) {
        this.wistermijn = wissenConfig.termijn();
        this.afleverbewijstermijn = afleverbewijsConfig.bewaartermijn();
        this.maxCursorleeftijd = feedConfig.maxCursorleeftijd();
    }

    /** Wanneer de sleutel van een notificatie die op {@code eindstatusOp} een eindstatus kreeg, gewist wordt. */
    public OffsetDateTime wissenOp(OffsetDateTime eindstatusOp) {
        return eindstatusOp.plus(wistermijn);
    }

    /** Wat vóór dit tijdstip ligt, valt buiten de bewaartermijn van het afleverbewijs. */
    public OffsetDateTime bewaargrens(OffsetDateTime nu) {
        return nu.minus(afleverbewijstermijn);
    }

    /** De maximale cursorleeftijd voor een dienstverlener zonder eigen afspraak in het register. */
    public Duration maxCursorleeftijd() {
        return maxCursorleeftijd;
    }
}
