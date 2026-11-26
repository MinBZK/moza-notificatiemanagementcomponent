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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leest de mappings met de configbouwer en de converters van Quarkus, bovenop de echte
 * {@code application.properties}; elke test overschrijft daar één waarde. De validator is die van
 * SmallRye en niet de variant die Quarkus bij het bouwen van de applicatie genereert.
 */
class ConfigMappingTest {

    // Deze waarden komen in de applicatie uit de omgeving; application.properties laat ze leeg.
    private static final Map<String, String> SLEUTEL = Map.of(
            "nmc.webhook.jwt.private-key", "sleutel",
            "nmc.webhook.jwt.key-id", "sleutel-1",
            "nmc.feed.source", "urn:nld:oin:00000000000000000000:systeem:nmc");

    @Test
    void applicationProperties_wordtGelezen() {
        SmallRyeConfig config = bouw(SLEUTEL);

        TaakConfig taak = config.getConfigMapping(TaakConfig.class);
        WebhookConfig webhook = config.getConfigMapping(WebhookConfig.class);

        assertEquals(20, taak.batch());
        assertEquals(Duration.ofMinutes(2), taak.lease());
        assertEquals(Duration.ofSeconds(30), webhook.herpogingWachttijd());
        assertEquals("sleutel-1", webhook.jwt().keyId());
        assertEquals(Duration.ofHours(1), webhook.jwt().jwksMaxAge());
        assertTrue(webhook.jwt().extraPublicKey().isEmpty(), "leeg buiten een sleutelwissel");
        assertTrue(webhook.jwt().extraKeyId().isEmpty(), "leeg buiten een sleutelwissel");
        assertEquals(Duration.ofDays(30), config.getConfigMapping(FeedConfig.class).maxCursorleeftijd());
        assertEquals(Duration.ofDays(7), config.getConfigMapping(VaststellingConfig.class).termijn());
        assertEquals(20, config.getConfigMapping(VerzendbudgetConfig.class).tokensPerMinuut());
    }

    @Test
    void extraSleutelVanEenSleutelwissel_wordtGelezen() {
        Map<String, String> waarden = new HashMap<>(SLEUTEL);
        waarden.put("nmc.webhook.jwt.extra-public-key", "publieke-sleutel");
        waarden.put("nmc.webhook.jwt.extra-key-id", "sleutel-2");

        WebhookConfig.Jwt jwt = bouw(waarden).getConfigMapping(WebhookConfig.class).jwt();

        assertEquals("publieke-sleutel", jwt.extraPublicKey().orElseThrow());
        assertEquals("sleutel-2", jwt.extraKeyId().orElseThrow());
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
            "nmc.webhook.pauze-wachttijd, 0s",
            "nmc.webhook.timeout, 0s",
            "nmc.webhook.jwt.geldigheid, 0s",
            "nmc.webhook.jwt.jwks-max-age, 0s",
            "nmc.webhook.jwt.jwks-max-age, 999ms",
            "nmc.webhook.jwt.private-key, ''",
            "nmc.webhook.jwt.private-key, ' '",
            "nmc.webhook.jwt.key-id, ' '",
            "nmc.webhook.jwt.issuer, ' '",
            "nmc.controle.interval, 0s",
            "nmc.controle.max-per-ronde, 0",
            "nmc.feed.max-pagina, 0",
            "nmc.feed.max-aanroepen-per-minuut, 0",
            "nmc.feed.cluster-epoch, -1",
            "nmc.feed.max-cursorleeftijd, 0s",
            "nmc.feed.source, /api/nmc/v1/notificaties",
            "nmc.feed.source, urn:nld:oin:123:systeem:nmc",
            "nmc.vaststelling.termijn, 0s",
            "nmc.vaststelling.callback-venster, -1s",
            "nmc.verzendbudget.tokens-per-minuut, 0",
            "nmc.verzendbudget.verzend-reservering, 0",
            "nmc.verzendbudget.navraag-reservering, -1",
            "nmc.verzendbudget.navraag-reservering, 0",
            "nmc.wissen.termijn, 0s",
            "nmc.afleverbewijs.bewaartermijn, -1d",
            "nmc.afleverbewijs.bewaartermijn, 0s",
            "nmc.fsc.jwks-bewaartijd, 0s"
    })
    void ongeldigeWaarde_weigertOpTeStarten(String sleutel, String waarde) {
        Map<String, String> waarden = new HashMap<>(SLEUTEL);
        waarden.put(sleutel, waarde);

        ConfigValidationException fout = assertThrows(ConfigValidationException.class, () -> bouw(waarden));

        assertTrue(fout.getMessage().contains(sleutel), fout.getMessage());
    }

    // Buiten het testprofiel, waar application.properties deze waarden leeg laat.
    @ParameterizedTest
    @ValueSource(strings = {"nmc.webhook.jwt.private-key", "nmc.webhook.jwt.key-id", "nmc.feed.source"})
    void verplichteWaardeNietGezet_weigertOpTeStarten(String sleutel) {
        Map<String, String> waarden = new HashMap<>(SLEUTEL);
        waarden.remove(sleutel);

        ConfigValidationException fout = assertThrows(ConfigValidationException.class, () -> bouw("prod", waarden));

        assertTrue(fout.getMessage().contains(sleutel), fout.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
            "nmc.taak.metriek-verversing, 0s",
            "nmc.webhook.jwt.jwks-max-age, 1s",
            "nmc.feed.cluster-epoch, 0",
            "nmc.vaststelling.callback-venster, 0s"
    })
    void grenswaarde_isToegestaan(String sleutel, String waarde) {
        Map<String, String> waarden = new HashMap<>(SLEUTEL);
        waarden.put(sleutel, waarde);

        assertDoesNotThrow(() -> bouw("test", waarden));
    }

    // De waarden waarmee de applicatie buiten het testprofiel start; die van FSC komen uit de omgeving.
    @Test
    void standaardwaarden_voldoenAanDeRegelsOverMeerdereProperties() {
        Map<String, String> waarden = new HashMap<>(SLEUTEL);
        waarden.put("nmc.fsc.jwks-url", "https://manager.example.nl/.well-known/jwks.json");
        waarden.put("nmc.fsc.uitgever", "00000000000000001000");
        waarden.put("nmc.fsc.inway-adres", "https://inway.example.nl:443");
        waarden.put("nmc.fsc.groep", "fsc-groep");
        waarden.put("nmc.fsc.dienst", "nmc");
        SmallRyeConfig config = bouw("prod", waarden);

        FscConfig fsc = config.getConfigMapping(FscConfig.class);
        assertTrue(fsc.ingeschakeld(), "buiten dev en test staat de controle van het token aan");
        assertEquals(Duration.ofSeconds(30), fsc.klokmarge());
        assertEquals(Duration.ofSeconds(5), fsc.jwksTimeout());
        assertEquals(Duration.ofHours(1), fsc.jwksBewaartijd());
        assertEquals(Duration.ofHours(1), fsc.jwksStoringstolerantie());
        assertDoesNotThrow(() -> validatie(config));
    }

    @Test
    void prodZonderFscWaarden_weigertOpTeStarten() {
        SmallRyeConfig config = bouw("prod", SLEUTEL);

        IllegalStateException fout = assertThrows(IllegalStateException.class, () -> validatie(config));

        assertTrue(fout.getMessage().contains("nmc.fsc.jwks-url"), fout.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void devEnTest_startenZonderFscWaarden(String profiel) {
        SmallRyeConfig config = bouw(profiel, SLEUTEL);

        assertEquals(false, config.getConfigMapping(FscConfig.class).ingeschakeld());
        assertDoesNotThrow(() -> validatie(config));
    }

    private static ConfigValidatie validatie(SmallRyeConfig config) {
        return new ConfigValidatie(
                config.getConfigMapping(TaakConfig.class),
                config.getConfigMapping(WebhookConfig.class),
                config.getConfigMapping(VerzendbudgetConfig.class),
                config.getConfigMapping(WissenConfig.class),
                config.getConfigMapping(AfleverbewijsConfig.class),
                config.getConfigMapping(FscConfig.class));
    }

    @Test
    void onbekendeSleutelOnderDePrefix_weigertOpTeStarten() {
        Map<String, String> waarden = new HashMap<>(SLEUTEL);
        waarden.put("nmc.taak.bestaat-niet", "1");

        ConfigValidationException fout = assertThrows(ConfigValidationException.class, () -> bouw(waarden));

        assertTrue(fout.getMessage().contains("nmc.taak.bestaat-niet"), fout.getMessage());
    }

    private static SmallRyeConfig bouw(Map<String, String> overschreven) {
        return bouw("test", overschreven);
    }

    private static SmallRyeConfig bouw(String profiel, Map<String, String> overschreven) {
        try {
            return ConfigUtils.emptyConfigBuilder()
                    .withProfile(profiel)
                    .withMapping(TaakConfig.class)
                    .withMapping(WebhookConfig.class)
                    .withMapping(ControleConfig.class)
                    .withMapping(FeedConfig.class)
                    .withMapping(VaststellingConfig.class)
                    .withMapping(VerzendbudgetConfig.class)
                    .withMapping(WissenConfig.class)
                    .withMapping(AfleverbewijsConfig.class)
                    .withMapping(FscConfig.class)
                    .withValidator(new BeanValidationConfigValidatorImpl())
                    .withSources(new PropertiesConfigSource(
                            ConfigMappingTest.class.getResource("/application.properties")))
                    .withSources(new PropertiesConfigSource(overschreven, "test", 500))
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
