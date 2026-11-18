package nl.rijksoverheid.moz.nmc.testhelper;

import nl.rijksoverheid.moz.nmc.config.TaakConfig;
import nl.rijksoverheid.moz.nmc.config.WebhookConfig;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Configuratie voor tests die een klasse zelf aanmaken, buiten de container om.
 */
public final class TestConfig {

    public static final Duration LEASE = Duration.ofMinutes(2);

    private TestConfig() {
    }

    public static TaakConfig taak() {
        TaakConfig config = mock(TaakConfig.class);
        when(config.interval()).thenReturn("off");
        when(config.batch()).thenReturn(10);
        when(config.lease()).thenReturn(LEASE);
        when(config.maxPogingen()).thenReturn(2);
        when(config.metriekVerversing()).thenReturn(Duration.ZERO);
        when(config.uitstel()).thenReturn(Duration.ofMinutes(5));

        return config;
    }

    public static WebhookConfig webhook(Duration timeout) {
        WebhookConfig config = mock(WebhookConfig.class);
        when(config.timeout()).thenReturn(timeout);

        return config;
    }

    public static WebhookConfig webhookJwt(String privateKey, String keyId, String issuer, Duration geldigheid) {
        WebhookConfig.Jwt jwt = mock(WebhookConfig.Jwt.class);
        when(jwt.privateKey()).thenReturn(privateKey);
        when(jwt.keyId()).thenReturn(keyId);
        when(jwt.issuer()).thenReturn(issuer);
        when(jwt.geldigheid()).thenReturn(geldigheid);

        WebhookConfig config = mock(WebhookConfig.class);
        when(config.jwt()).thenReturn(jwt);

        return config;
    }
}
