package nl.rijksoverheid.moz.nmc.config;

import io.smallrye.config.ConfigMapping;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;

/**
 * De instellingen van het afleverbewijs onder {@code nmc.afleverbewijs}. Een ongeldige waarde laat
 * de applicatie niet starten.
 */
@ConfigMapping(prefix = "nmc.afleverbewijs")
public interface AfleverbewijsConfig {

    /**
     * Hoe lang de notificatie, haar pogingen en haar events bewaard blijven.
     */
    @DurationMin(nanos = 1)
    Duration bewaartermijn();
}
