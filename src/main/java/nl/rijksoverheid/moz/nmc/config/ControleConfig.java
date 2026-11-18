package nl.rijksoverheid.moz.nmc.config;

import io.smallrye.config.ConfigMapping;
import jakarta.validation.constraints.Min;
import org.hibernate.validator.constraints.time.DurationMin;

import java.time.Duration;

/**
 * De instellingen van de controletaak onder {@code nmc.controle}. Een ongeldige waarde laat de
 * applicatie niet starten.
 */
@ConfigMapping(prefix = "nmc.controle")
public interface ControleConfig {

    /**
     * Hoe vaak de controletaak draait.
     */
    @DurationMin(nanos = 1)
    Duration interval();

    /**
     * Het grootste aantal notificaties dat één ronde herstelt.
     */
    @Min(1)
    int maxPerRonde();
}
