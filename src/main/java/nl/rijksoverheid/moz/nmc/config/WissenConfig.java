package nl.rijksoverheid.moz.nmc.config;

import io.smallrye.config.ConfigMapping;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;

/**
 * De instellingen van het wissen onder {@code nmc.wissen}. Een ongeldige waarde laat de applicatie
 * niet starten.
 */
@ConfigMapping(prefix = "nmc.wissen")
public interface WissenConfig {

    /**
     * Hoe lang na de eindstatus de sleutel van een notificatie wordt gewist.
     */
    @DurationMin(nanos = 1)
    Duration termijn();
}
