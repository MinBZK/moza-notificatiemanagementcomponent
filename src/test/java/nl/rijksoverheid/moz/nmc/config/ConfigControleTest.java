package nl.rijksoverheid.moz.nmc.config;

import nl.rijksoverheid.moz.nmc.testhelper.TestConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigControleTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final VerzendbudgetConfig BUDGET = TestConfig.verzendbudget(10, 5, 5);

    @Test
    void timeoutKorterDanLease_start() {
        Duration timeout = TestConfig.LEASE.minusSeconds(1);

        assertDoesNotThrow(() -> new ConfigControle(TestConfig.taak(), TestConfig.webhook(timeout), BUDGET));
    }

    @Test
    void timeoutNietKorterDanLease_weigertOpTeStarten() {
        IllegalStateException fout = assertThrows(IllegalStateException.class,
                () -> new ConfigControle(TestConfig.taak(), TestConfig.webhook(TestConfig.LEASE), BUDGET));

        assertTrue(fout.getMessage().contains("nmc.webhook.timeout"), fout.getMessage());
    }

    @Test
    void reserveringenSamenMeerDanHetBudget_weigertOpTeStarten() {
        IllegalStateException fout = assertThrows(IllegalStateException.class,
                () -> new ConfigControle(TestConfig.taak(), TestConfig.webhook(TIMEOUT), TestConfig.verzendbudget(10, 6, 5)));

        assertTrue(fout.getMessage().contains("nmc.verzendbudget.tokens-per-minuut"), fout.getMessage());
    }
}
