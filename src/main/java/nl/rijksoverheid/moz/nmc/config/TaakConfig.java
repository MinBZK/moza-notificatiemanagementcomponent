package nl.rijksoverheid.moz.nmc.config;

import io.smallrye.config.ConfigMapping;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;

/**
 * De instellingen van de taken onder {@code nmc.taak}: de worker, de claim en het uitstel. Een
 * ongeldige waarde laat de applicatie niet starten.
 */
@ConfigMapping(prefix = "nmc.taak")
public interface TaakConfig {

    /**
     * Hoe vaak een ronde per taaksoort start; {@code off} zet de lus uit. Alleen de
     * {@code @Scheduled}-expressies van de worker lezen deze waarde. Hij staat hier omdat een mapping
     * elke property onder zijn prefix moet kennen; zonder dit lid start de applicatie niet.
     */
    String interval();

    /**
     * Het grootste aantal taken dat een ronde claimt.
     */
    @Min(1)
    int batch();

    /**
     * Hoe lang een worker een geclaimde taak vasthoudt.
     */
    @DurationMin(nanos = 1)
    Duration lease();

    /**
     * Na hoeveel mislukte pogingen een taak op mislukt gaat.
     */
    @Min(1)
    int maxPogingen();

    /**
     * Hoe lang een telling voor de taakmetrieken geldt.
     */
    @DurationMin
    Duration metriekVerversing();

    /**
     * Hoe lang een taak wacht als een externe dienst de aanroep weigert.
     */
    @DurationMin(nanos = 1)
    Duration uitstel();
}
