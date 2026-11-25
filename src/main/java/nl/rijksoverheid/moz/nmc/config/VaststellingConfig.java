package nl.rijksoverheid.moz.nmc.config;

import io.smallrye.config.ConfigMapping;
import org.hibernate.validator.constraints.time.DurationMin;

import java.time.Duration;

/**
 * De instellingen van de vaststelling van een bezorging onder {@code nmc.vaststelling}. Een ongeldige
 * waarde laat de applicatie niet starten.
 */
@ConfigMapping(prefix = "nmc.vaststelling")
public interface VaststellingConfig {

    /**
     * Hoe lang na de bezorging een faalreceipt die bezorging nog kan herroepen.
     */
    @DurationMin(nanos = 1)
    Duration termijn();

    /**
     * Hoe lang de vaststeltaak na de termijn nog op een late receipt wacht.
     */
    @DurationMin
    Duration callbackVenster();
}
