package nl.rijksoverheid.moz.nmc.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import nl.rijksoverheid.moz.nmc.testhelper.TestConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ConfigValidatieTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration WEEK = Duration.ofDays(7);

    @Test
    void geldigeConfiguratie_start() {
        assertDoesNotThrow(
                () -> validatie(TestConfig.LEASE.minusSeconds(1), TestConfig.verzendbudget(10, 5, 5), WEEK, WEEK));
    }

    @Test
    void timeoutNietKorterDanLease_weigertOpTeStarten() {
        IllegalStateException fout = assertThrows(
                IllegalStateException.class,
                () -> validatie(TestConfig.LEASE, TestConfig.verzendbudget(10, 5, 5), WEEK, WEEK));

        assertTrue(fout.getMessage().contains("nmc.webhook.timeout"), fout.getMessage());
    }

    @Test
    void reserveringenSamenMeerDanHetBudget_weigertOpTeStarten() {
        IllegalStateException fout = assertThrows(
                IllegalStateException.class, () -> validatie(TIMEOUT, TestConfig.verzendbudget(10, 6, 5), WEEK, WEEK));

        assertTrue(fout.getMessage().contains("nmc.verzendbudget.tokens-per-minuut"), fout.getMessage());
    }

    @Test
    void bewaartermijnKorterDanWistermijn_weigertOpTeStarten() {
        IllegalStateException fout = assertThrows(
                IllegalStateException.class,
                () -> validatie(TIMEOUT, TestConfig.verzendbudget(10, 5, 5), Duration.ofDays(8), WEEK));

        assertTrue(fout.getMessage().contains("nmc.afleverbewijs.bewaartermijn"), fout.getMessage());
    }

    private static ConfigValidatie validatie(
            Duration timeout, VerzendbudgetConfig verzendbudget, Duration wistermijn, Duration bewaartermijn) {
        return new ConfigValidatie(
                TestConfig.taak(),
                TestConfig.webhook(timeout),
                verzendbudget,
                TestConfig.wissen(wistermijn),
                TestConfig.afleverbewijs(bewaartermijn));
    }
}
