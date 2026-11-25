package nl.rijksoverheid.moz.nmc.config;

import io.smallrye.config.ConfigMapping;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.Optional;
import org.hibernate.validator.constraints.time.DurationMin;

/**
 * De instellingen van de terugkoppeltaak onder {@code nmc.webhook}. Een ongeldige waarde laat de
 * applicatie niet starten.
 */
@ConfigMapping(prefix = "nmc.webhook")
public interface WebhookConfig {

    /**
     * Hoe snel de taak na een ronde zonder events opnieuw kijkt.
     */
    @DurationMin(nanos = 1)
    Duration interval();

    /**
     * Het aantal events per aanroep, als het register daar niets over zegt.
     */
    @Min(1)
    int bundel();

    /**
     * Na hoeveel mislukte leveringen op rij de webhook pauzeert, als het register daar niets over zegt.
     */
    @Min(1)
    int maxMislukkingen();

    /**
     * De wachttijd na een mislukte levering; verdubbelt per mislukking.
     */
    @DurationMin(nanos = 1)
    Duration herpogingWachttijd();

    /**
     * De wachttijd tijdens een pauze; verdubbelt per mislukking.
     */
    @DurationMin(nanos = 1)
    Duration pauzeWachttijd();

    /**
     * De time-out per aanroep, in totaal.
     */
    @DurationMin(nanos = 1)
    Duration timeout();

    Jwt jwt();

    /**
     * De ondertekening van de JWT op elke webhook-aanroep.
     */
    interface Jwt {

        /**
         * De RSA-sleutel als PKCS#8-PEM, met of zonder kop- en voetregel.
         */
        @NotBlank
        String privateKey();

        /**
         * De {@code kid} waaronder de publieke sleutel in de JWKS staat.
         */
        @NotBlank
        String keyId();

        /**
         * De {@code iss} van de JWT.
         */
        @NotBlank
        String issuer();

        /**
         * Hoe lang een JWT na uitgifte geldig is.
         */
        @DurationMin(nanos = 1)
        Duration geldigheid();

        /**
         * Hoe lang een ontvanger de JWKS mag bewaren: de {@code max-age} van het antwoord.
         */
        @DurationMin(seconds = 1)
        Duration jwksMaxAge();

        /**
         * De publieke RSA-sleutel (X.509-PEM, met of zonder kop- en voetregel) die de JWKS tijdens
         * een rotatie naast de eigen sleutel publiceert: de volgende sleutel vóór de wissel, de
         * vorige erna.
         */
        Optional<String> extraPublicKey();

        /**
         * De {@code kid} van de extra sleutel.
         */
        Optional<String> extraKeyId();
    }
}
