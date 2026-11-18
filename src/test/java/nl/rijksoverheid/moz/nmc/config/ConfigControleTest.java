package nl.rijksoverheid.moz.nmc.config;

import nl.rijksoverheid.moz.nmc.testhelper.TestConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigControleTest {

    @Test
    void timeoutKorterDanLease_start() {
        Duration timeout = TestConfig.LEASE.minusSeconds(1);

        assertDoesNotThrow(() -> new ConfigControle(TestConfig.taak(), TestConfig.webhook(timeout)));
    }

    @Test
    void timeoutNietKorterDanLease_weigertOpTeStarten() {
        IllegalStateException fout = assertThrows(IllegalStateException.class,
                () -> new ConfigControle(TestConfig.taak(), TestConfig.webhook(TestConfig.LEASE)));

        assertTrue(fout.getMessage().contains("nmc.webhook.timeout"), fout.getMessage());
    }
}
