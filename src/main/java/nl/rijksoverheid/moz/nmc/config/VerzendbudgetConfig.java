package nl.rijksoverheid.moz.nmc.config;

import io.smallrye.config.ConfigMapping;
import jakarta.validation.constraints.Min;

/**
 * De instellingen van het verzendbudget onder {@code nmc.verzendbudget}. Een ongeldige waarde laat de
 * applicatie niet starten.
 */
@ConfigMapping(prefix = "nmc.verzendbudget")
public interface VerzendbudgetConfig {

    /**
     * Het aantal aanroepen van NotifyNL per minuut.
     */
    @Min(1)
    int tokensPerMinuut();

    /**
     * Het deel van het budget dat voor verzenden is gereserveerd.
     */
    @Min(1)
    int verzendReservering();

    /**
     * Het deel van het budget dat voor de navraag is gereserveerd.
     */
    @Min(1)
    int navraagReservering();
}
