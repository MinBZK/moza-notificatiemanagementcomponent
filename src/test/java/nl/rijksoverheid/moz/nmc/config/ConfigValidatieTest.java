package nl.rijksoverheid.moz.nmc.config;

import nl.rijksoverheid.moz.nmc.testhelper.TestConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class ConfigValidatieTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration WEEK = Duration.ofDays(7);

    @Test
    void geldigeConfiguratie_start() {
        assertDoesNotThrow(() -> validatie(TestConfig.LEASE.minusSeconds(1), TestConfig.verzendbudget(10, 5, 5), WEEK, WEEK));
    }

    @Test
    void timeoutNietKorterDanLease_weigertOpTeStarten() {
        IllegalStateException fout = assertThrows(IllegalStateException.class,
                () -> validatie(TestConfig.LEASE, TestConfig.verzendbudget(10, 5, 5), WEEK, WEEK));

        assertTrue(fout.getMessage().contains("nmc.webhook.timeout"), fout.getMessage());
    }

    @Test
    void reserveringenSamenMeerDanHetBudget_weigertOpTeStarten() {
        IllegalStateException fout = assertThrows(IllegalStateException.class,
                () -> validatie(TIMEOUT, TestConfig.verzendbudget(10, 6, 5), WEEK, WEEK));

        assertTrue(fout.getMessage().contains("nmc.verzendbudget.tokens-per-minuut"), fout.getMessage());
    }

    @Test
    void bewaartermijnKorterDanWistermijn_weigertOpTeStarten() {
        IllegalStateException fout = assertThrows(IllegalStateException.class,
                () -> validatie(TIMEOUT, TestConfig.verzendbudget(10, 5, 5), Duration.ofDays(8), WEEK));

        assertTrue(fout.getMessage().contains("nmc.afleverbewijs.bewaartermijn"), fout.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://manager.example.nl/.well-known/jwks.json", "http://localhost:8443/jwks.json",
            "http://127.0.0.1:8443/jwks.json"})
    void fscIngeschakeldMetAlleWaarden_start(String jwksUrl) {
        assertDoesNotThrow(() -> validatie(TestConfig.fsc(URI.create(jwksUrl))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"jwks-url", "uitgever", "inway-adres", "groep", "dienst"})
    void fscIngeschakeldZonderVerplichteWaarde_weigertOpTeStarten(String ontbrekend) {
        FscConfig fsc = TestConfig.fsc(URI.create("https://manager.example.nl/.well-known/jwks.json"));

        switch (ontbrekend) {
            case "jwks-url" -> when(fsc.jwksUrl()).thenReturn(Optional.empty());
            case "uitgever" -> when(fsc.uitgever()).thenReturn(Optional.empty());
            case "inway-adres" -> when(fsc.inwayAdres()).thenReturn(Optional.empty());
            case "groep" -> when(fsc.groep()).thenReturn(Optional.empty());
            default -> when(fsc.dienst()).thenReturn(Optional.empty());
        }

        IllegalStateException fout = assertThrows(IllegalStateException.class, () -> validatie(fsc));

        assertTrue(fout.getMessage().contains("nmc.fsc." + ontbrekend), fout.getMessage());
    }

    // Een JWKS zonder TLS van een andere machine is onderweg te vervangen.
    @ParameterizedTest
    @ValueSource(strings = {"http://manager.example.nl/.well-known/jwks.json", "http://localhost.example.nl/jwks.json"})
    void fscJwksZonderTlsVanEenAndereMachine_weigertOpTeStarten(String jwksUrl) {
        IllegalStateException fout = assertThrows(IllegalStateException.class,
                () -> validatie(TestConfig.fsc(URI.create(jwksUrl))));

        assertTrue(fout.getMessage().contains("nmc.fsc.jwks-url"), fout.getMessage());
    }

    private static ConfigValidatie validatie(Duration timeout, VerzendbudgetConfig verzendbudget, Duration wistermijn,
                                             Duration bewaartermijn) {
        return new ConfigValidatie(TestConfig.taak(), TestConfig.webhook(timeout), verzendbudget,
                TestConfig.wissen(wistermijn), TestConfig.afleverbewijs(bewaartermijn), TestConfig.fscUit());
    }

    private static ConfigValidatie validatie(FscConfig fsc) {
        return new ConfigValidatie(TestConfig.taak(), TestConfig.webhook(TIMEOUT), TestConfig.verzendbudget(10, 5, 5),
                TestConfig.wissen(WEEK), TestConfig.afleverbewijs(WEEK), fsc);
    }
}
