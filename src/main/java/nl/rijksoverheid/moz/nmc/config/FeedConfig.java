package nl.rijksoverheid.moz.nmc.config;

import io.smallrye.config.ConfigMapping;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.time.DurationMin;

import java.time.Duration;

/**
 * De instellingen van de eventfeed onder {@code nmc.feed}. Een ongeldige waarde laat de applicatie
 * niet starten.
 */
@ConfigMapping(prefix = "nmc.feed")
public interface FeedConfig {

    /**
     * Het grootste aantal events in één pagina.
     */
    @Min(1)
    int maxPagina();

    /**
     * Het aantal feedaanroepen dat een dienstverlener per minuut mag doen.
     */
    @Min(1)
    int maxAanroepenPerMinuut();

    /**
     * Het cluster-epoch van de cursors; beheer verhoogt het na een herstel uit back-up.
     */
    @Min(0)
    int clusterEpoch();

    /**
     * Hoe lang een bevestigde cursor of leverpositie events tegen opruimen beschermt, als het
     * register daar niets over zegt.
     */
    @DurationMin(nanos = 1)
    Duration maxCursorleeftijd();

    /**
     * De {@code source} van elk CloudEvent: de URN van dit systeem volgens het NL GOV profiel,
     * {@code urn:nld:oin:<OIN>:systeem:<naam>}.
     */
    @Pattern(regexp = "urn:nld:oin:[0-9]{20}:systeem:[A-Za-z0-9._-]+")
    String source();
}
