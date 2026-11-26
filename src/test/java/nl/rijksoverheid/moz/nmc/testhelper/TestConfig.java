package nl.rijksoverheid.moz.nmc.testhelper;

import nl.rijksoverheid.moz.nmc.config.AfleverbewijsConfig;
import nl.rijksoverheid.moz.nmc.config.FeedConfig;
import nl.rijksoverheid.moz.nmc.config.FscConfig;
import nl.rijksoverheid.moz.nmc.config.TaakConfig;
import nl.rijksoverheid.moz.nmc.config.VaststellingConfig;
import nl.rijksoverheid.moz.nmc.config.VerzendbudgetConfig;
import nl.rijksoverheid.moz.nmc.config.WebhookConfig;
import nl.rijksoverheid.moz.nmc.config.WissenConfig;
import nl.rijksoverheid.moz.nmc.service.Opruimtermijnen;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

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
        return taak(2);
    }

    public static TaakConfig taak(int maxPogingen) {
        TaakConfig config = mock(TaakConfig.class);
        when(config.interval()).thenReturn("off");
        when(config.batch()).thenReturn(10);
        when(config.lease()).thenReturn(LEASE);
        when(config.maxPogingen()).thenReturn(maxPogingen);
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

    /**
     * Een webhook-configuratie met de extra sleutel van een rotatie; {@code null} laat een waarde weg.
     */
    public static WebhookConfig webhookJwtMetExtraSleutel(String privateKey, String keyId, String extraPublicKey,
                                                          String extraKeyId, Duration jwksMaxAge) {
        WebhookConfig config = webhookJwt(privateKey, keyId, "https://nmc.example.nl", Duration.ofMinutes(2));
        WebhookConfig.Jwt jwt = config.jwt();
        when(jwt.extraPublicKey()).thenReturn(Optional.ofNullable(extraPublicKey));
        when(jwt.extraKeyId()).thenReturn(Optional.ofNullable(extraKeyId));
        when(jwt.jwksMaxAge()).thenReturn(jwksMaxAge);

        return config;
    }

    public static FeedConfig feed() {
        FeedConfig config = mock(FeedConfig.class);
        when(config.maxPagina()).thenReturn(10);
        when(config.maxAanroepenPerMinuut()).thenReturn(60);
        when(config.clusterEpoch()).thenReturn(1);
        when(config.maxCursorleeftijd()).thenReturn(Duration.ofDays(30));
        when(config.source()).thenReturn("urn:nld:oin:00000000000000000000:systeem:nmc");

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
        when(config.tokensPerMinuut()).thenReturn(tokensPerMinuut);
        when(config.verzendReservering()).thenReturn(verzendReservering);
        when(config.navraagReservering()).thenReturn(navraagReservering);

        return config;
    }

    /** De controle van het FSC-toegangstoken uit, zoals in dev en test. */
    public static FscConfig fscUit() {
        FscConfig config = mock(FscConfig.class);
        when(config.ingeschakeld()).thenReturn(false);
        when(config.jwksUrl()).thenReturn(Optional.empty());
        when(config.uitgever()).thenReturn(Optional.empty());
        when(config.inwayAdres()).thenReturn(Optional.empty());
        when(config.groep()).thenReturn(Optional.empty());
        when(config.dienst()).thenReturn(Optional.empty());

        return config;
    }

    /** De controle van het FSC-toegangstoken aan, met de waarden van {@link FscManagerStub}. */
    public static FscConfig fsc(URI jwksUrl) {
        return fsc(jwksUrl, Duration.ofHours(1));
    }

    /** Als {@link #fsc(URI)}, met een eigen bewaartijd voor de JWKS. */
    public static FscConfig fsc(URI jwksUrl, Duration jwksBewaartijd) {
        FscConfig config = mock(FscConfig.class);
        when(config.jwksBewaartijd()).thenReturn(jwksBewaartijd);
        when(config.ingeschakeld()).thenReturn(true);
        when(config.jwksUrl()).thenReturn(Optional.ofNullable(jwksUrl));
        when(config.uitgever()).thenReturn(Optional.of(FscManagerStub.UITGEVER));
        when(config.inwayAdres()).thenReturn(Optional.of(FscManagerStub.INWAY_ADRES));
        when(config.groep()).thenReturn(Optional.of(FscManagerStub.GROEP));
        when(config.dienst()).thenReturn(Optional.of(FscManagerStub.DIENST));
        when(config.klokmarge()).thenReturn(Duration.ofSeconds(30));
        when(config.jwksTimeout()).thenReturn(Duration.ofSeconds(2));
        when(config.jwksStoringstolerantie()).thenReturn(Duration.ofHours(1));

        return config;
    }

    public static WissenConfig wissen(Duration termijn) {
        WissenConfig config = mock(WissenConfig.class);
        when(config.termijn()).thenReturn(termijn);

        return config;
    }

    public static AfleverbewijsConfig afleverbewijs(Duration bewaartermijn) {
        AfleverbewijsConfig config = mock(AfleverbewijsConfig.class);
        when(config.bewaartermijn()).thenReturn(bewaartermijn);

        return config;
    }

    public static Opruimtermijnen opruimtermijnen(Duration wistermijn, Duration bewaartermijn, Duration maxCursorleeftijd) {
        return new Opruimtermijnen(wissen(wistermijn), afleverbewijs(bewaartermijn), feedMetCursorleeftijd(maxCursorleeftijd));
    }
}
