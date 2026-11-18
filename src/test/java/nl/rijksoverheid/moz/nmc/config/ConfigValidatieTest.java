package nl.rijksoverheid.moz.nmc.config;

import io.smallrye.config.ConfigValidationException;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.validator.BeanValidationConfigValidatorImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigValidatieTest {

    private static final Map<String, String> GELDIG = Map.ofEntries(
            Map.entry("nmc.taak.interval", "1s"),
            Map.entry("nmc.taak.batch", "20"),
            Map.entry("nmc.taak.lease", "PT2M"),
            Map.entry("nmc.taak.max-pogingen", "5"),
            Map.entry("nmc.taak.metriek-verversing", "PT0S"),
            Map.entry("nmc.taak.uitstel", "PT5M"),
            Map.entry("nmc.webhook.interval", "PT10S"),
            Map.entry("nmc.webhook.bundel", "100"),
            Map.entry("nmc.webhook.max-mislukkingen", "5"),
            Map.entry("nmc.webhook.herpoging-wachttijd", "PT30S"),
            Map.entry("nmc.webhook.pauze-wachttijd", "PT15M"),
            Map.entry("nmc.webhook.timeout", "PT10S"),
            Map.entry("nmc.webhook.jwt.private-key", "sleutel"),
            Map.entry("nmc.webhook.jwt.key-id", "sleutel-1"),
            Map.entry("nmc.webhook.jwt.issuer", "https://example.test/nmc"),
            Map.entry("nmc.webhook.jwt.geldigheid", "PT5M"));

    @Test
    void geldigeWaarden_wordenGelezen() {
        SmallRyeConfig config = bouw(GELDIG);

        assertEquals(20, config.getConfigMapping(TaakConfig.class).batch());
        assertEquals(5, config.getConfigMapping(TaakConfig.class).maxPogingen());
        assertEquals(Duration.ofSeconds(30), config.getConfigMapping(WebhookConfig.class).herpogingWachttijd());
    }

    @ParameterizedTest
    @CsvSource({
            "nmc.taak.batch, 0",
            "nmc.taak.lease, PT0S",
            "nmc.taak.max-pogingen, 0",
            "nmc.taak.uitstel, PT0S",
            "nmc.taak.metriek-verversing, PT-1S",
            "nmc.webhook.interval, PT0S",
            "nmc.webhook.bundel, 0",
            "nmc.webhook.max-mislukkingen, 0",
            "nmc.webhook.herpoging-wachttijd, PT0S",
            "nmc.webhook.pauze-wachttijd, PT-1S",
            "nmc.webhook.timeout, PT0S",
            "nmc.webhook.jwt.geldigheid, PT0S"
    })
    void ongeldigeWaarde_weigertOpTeStarten(String sleutel, String waarde) {
        Map<String, String> waarden = new HashMap<>(GELDIG);
        waarden.put(sleutel, waarde);

        ConfigValidationException fout = assertThrows(ConfigValidationException.class, () -> bouw(waarden));

        assertTrue(fout.getMessage().contains(sleutel), fout.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"nmc.webhook.jwt.private-key", "nmc.webhook.jwt.key-id", "nmc.webhook.jwt.issuer"})
    void ontbrekendeWaarde_weigertOpTeStarten(String sleutel) {
        Map<String, String> waarden = new HashMap<>(GELDIG);
        waarden.remove(sleutel);

        ConfigValidationException fout = assertThrows(ConfigValidationException.class, () -> bouw(waarden));

        assertTrue(fout.getMessage().contains(sleutel), fout.getMessage());
    }

    private static SmallRyeConfig bouw(Map<String, String> waarden) {
        return new SmallRyeConfigBuilder()
                .withMapping(TaakConfig.class)
                .withMapping(WebhookConfig.class)
                .withValidator(new BeanValidationConfigValidatorImpl())
                .withSources(new PropertiesConfigSource(waarden, "test"))
                .build();
    }
}
