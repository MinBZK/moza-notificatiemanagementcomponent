package nl.rijksoverheid.moz.nmc.testhelper;

import nl.rijksoverheid.moz.nmc.config.FeedConfig;
import nl.rijksoverheid.moz.nmc.config.TaakConfig;
import nl.rijksoverheid.moz.nmc.config.VaststellingConfig;
import nl.rijksoverheid.moz.nmc.config.VerzendbudgetConfig;
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

    public static FeedConfig feed() {
        FeedConfig config = mock(FeedConfig.class);
        when(config.maxPagina()).thenReturn(10);
        when(config.maxAanroepenPerMinuut()).thenReturn(60);
        when(config.clusterEpoch()).thenReturn(1);
        when(config.maxCursorleeftijd()).thenReturn(Duration.ofDays(30));

        return config;
    }

    public static FeedConfig feedMetAanroeplimiet(int maxAanroepenPerMinuut) {
        FeedConfig config = feed();
        when(config.maxAanroepenPerMinuut()).thenReturn(maxAanroepenPerMinuut);

        return config;
    }

    public static FeedConfig feedMetCursorleeftijd(Duration maxCursorleeftijd) {
        FeedConfig config = feed();
        when(config.maxCursorleeftijd()).thenReturn(maxCursorleeftijd);

        return config;
    }

    public static VaststellingConfig vaststelling(Duration termijn, Duration callbackVenster) {
        VaststellingConfig config = mock(VaststellingConfig.class);
        when(config.termijn()).thenReturn(termijn);
        when(config.callbackVenster()).thenReturn(callbackVenster);

        return config;
    }

    public static VerzendbudgetConfig verzendbudget(int tokensPerMinuut, int verzendReservering, int navraagReservering) {
        VerzendbudgetConfig config = mock(VerzendbudgetConfig.class);
        when(config.notifyService()).thenReturn("standaard");
        when(config.tokensPerMinuut()).thenReturn(tokensPerMinuut);
        when(config.verzendReservering()).thenReturn(verzendReservering);
        when(config.navraagReservering()).thenReturn(navraagReservering);

        return config;
    }
}
