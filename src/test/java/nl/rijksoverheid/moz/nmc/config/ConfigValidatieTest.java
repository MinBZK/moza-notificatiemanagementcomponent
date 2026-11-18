package nl.rijksoverheid.moz.nmc.config;

import io.quarkus.runtime.configuration.ConfigUtils;
import io.smallrye.config.ConfigValidationException;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.validator.BeanValidationConfigValidatorImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leest de mappings met de configbouwer en de converters van Quarkus, bovenop de echte
 * {@code application.properties}; elke test overschrijft daar één waarde. De validator is die van
 * SmallRye en niet de variant die Quarkus bij het bouwen van de applicatie genereert.
 */
class ConfigValidatieTest {

    // De sleutel komt in de applicatie uit de omgeving; application.properties laat hem leeg.
    private static final Map<String, String> SLEUTEL = Map.of(
            "nmc.webhook.jwt.private-key", "sleutel",
            "nmc.webhook.jwt.key-id", "sleutel-1");

    @Test
    void applicationProperties_wordtGelezen() {
        SmallRyeConfig config = bouw(SLEUTEL);

        TaakConfig taak = config.getConfigMapping(TaakConfig.class);
        WebhookConfig webhook = config.getConfigMapping(WebhookConfig.class);

        assertEquals(20, taak.batch());
        assertEquals(Duration.ofMinutes(2), taak.lease());
        assertEquals(Duration.ofSeconds(30), webhook.herpogingWachttijd());
        assertEquals("sleutel-1", webhook.jwt().keyId());
    }

    @ParameterizedTest
    @CsvSource({
            "nmc.taak.batch, 0",
            "nmc.taak.batch, -1",
            "nmc.taak.lease, 0s",
            "nmc.taak.max-pogingen, 0",
            "nmc.taak.metriek-verversing, -1s",
            "nmc.taak.uitstel, 0s",
            "nmc.webhook.interval, 0s",
            "nmc.webhook.bundel, 0",
            "nmc.webhook.max-mislukkingen, 0",
            "nmc.webhook.herpoging-wachttijd, 0s",
            "nmc.webhook.pauze-wachttijd, -1s",
            "nmc.webhook.timeout, 0s",
            "nmc.webhook.jwt.geldigheid, 0s",
            "nmc.webhook.jwt.private-key, ''",
            "nmc.webhook.jwt.private-key, ' '",
            "nmc.webhook.jwt.key-id, ' '",
            "nmc.webhook.jwt.issuer, ' '"
    })
    void ongeldigeWaarde_weigertOpTeStarten(String sleutel, String waarde) {
        Map<String, String> waarden = new HashMap<>(SLEUTEL);
        waarden.put(sleutel, waarde);

        ConfigValidationException fout = assertThrows(ConfigValidationException.class, () -> bouw(waarden));

        assertTrue(fout.getMessage().contains(sleutel), fout.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"nmc.webhook.jwt.private-key", "nmc.webhook.jwt.key-id"})
    void sleutelNietGezet_weigertOpTeStarten(String sleutel) {
        Map<String, String> waarden = new HashMap<>(SLEUTEL);
        waarden.remove(sleutel);

        ConfigValidationException fout = assertThrows(ConfigValidationException.class, () -> bouw(waarden));

        assertTrue(fout.getMessage().contains(sleutel), fout.getMessage());
    }

    @Test
    void onbekendeSleutelOnderDePrefix_weigertOpTeStarten() {
        Map<String, String> waarden = new HashMap<>(SLEUTEL);
        waarden.put("nmc.taak.bestaat-niet", "1");

        ConfigValidationException fout = assertThrows(ConfigValidationException.class, () -> bouw(waarden));

        assertTrue(fout.getMessage().contains("nmc.taak.bestaat-niet"), fout.getMessage());
    }

    private static SmallRyeConfig bouw(Map<String, String> overschreven) {
        try {
            return ConfigUtils.emptyConfigBuilder()
                    .withProfile("test")
                    .withMapping(TaakConfig.class)
                    .withMapping(WebhookConfig.class)
                    .withValidator(new BeanValidationConfigValidatorImpl())
                    .withSources(new PropertiesConfigSource(
                            ConfigValidatieTest.class.getResource("/application.properties")))
                    .withSources(new PropertiesConfigSource(overschreven, "test", 500))
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
