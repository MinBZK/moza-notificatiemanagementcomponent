package nl.rijksoverheid.moz.nmc.service;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookUrlValidatorTest {

    private static final WebhookUrlValidator VALIDATOR = new WebhookUrlValidator();

    private static final String SCHEME = "alleen een absolute https-URL is toegestaan";
    private static final String IP_ADRES = "een IP-adres als host is niet toegestaan";
    private static final String INTERNE_HOSTNAAM = "een interne hostnaam is niet toegestaan";
    private static final String GEEN_HOSTNAAM = "geen geldige hostnaam";

    @Test
    void geldigeHttpsUrl_wordtGeaccepteerd() {
        assertGeaccepteerd("https://consument.example.nl/status");
    }

    @Test
    void poortEnQueryZijnToegestaan() {
        assertGeaccepteerd("https://consument.example.nl:8443/pad?notificatie=1");
    }

    @Test
    void hoofdletterSchemeWordtGenormaliseerdNaarKleineLetters() {
        // De uitgaande REST-client vergelijkt het scheme hoofdlettergevoelig, dus "HTTPS"
        // mag niet ongewijzigd worden opgeslagen (anders gaat de callback over plain HTTP).
        assertEquals("https://consument.example.nl/status",
                VALIDATOR.normaliseer(URI.create("HTTPS://consument.example.nl/status")));
    }

    @Test
    void kleineletterSchemeWordtOngewijzigdTeruggegeven() {
        assertEquals("https://consument.example.nl:8443/pad?notificatie=1",
                VALIDATOR.normaliseer(URI.create("https://consument.example.nl:8443/pad?notificatie=1")));
    }

    @Test
    void uriZonderScheme_wordtOngewijzigdTeruggegeven() {
        assertEquals("/alleen-een-pad", VALIDATOR.normaliseer(URI.create("/alleen-een-pad")));
    }

    @Test
    void zonderUrl_wordtGeaccepteerdEnGeeftNull() {
        assertDoesNotThrow(() -> VALIDATOR.valideer(null));
        assertNull(VALIDATOR.normaliseer(null));
    }

    @Test
    void anderSchemaDanHttps_wordtGeweigerd() {
        assertGeweigerd("http://consument.example.nl/status", SCHEME);
        assertGeweigerd("ftp://consument.example.nl/status", SCHEME);
        assertGeweigerd("file:///etc/passwd", SCHEME);
    }

    @Test
    void relatieveUrl_wordtGeweigerd() {
        assertGeweigerd("/alleen-een-pad", SCHEME);
    }

    @Test
    void gebruikersinfo_wordtGeweigerd() {
        assertGeweigerd("https://gebruiker:wachtwoord@consument.example.nl/status", "gebruikersinfo");
    }

    @Test
    void ipAdres_wordtGeweigerd() {
        assertGeweigerd("https://127.0.0.1/status", IP_ADRES);
        assertGeweigerd("https://10.0.0.1:8080/status", IP_ADRES);
        assertGeweigerd("https://169.254.169.254/latest/meta-data/", IP_ADRES);
        assertGeweigerd("https://[::1]/status", IP_ADRES);
        assertGeweigerd("https://[fd00::1]/status", IP_ADRES);
        // Alleen de haakjes-regel weigert deze: het laatste label ("254]") is niet numeriek.
        assertGeweigerd("https://[::ffff:169.254.169.254]/status", IP_ADRES);
        assertGeweigerd("https://[::ffff:127.0.0.1]/status", IP_ADRES);
    }

    @Test
    void korteIpVormen_wordenGeweigerd() {
        assertGeweigerd("https://127.1/status", GEEN_HOSTNAAM);
        assertGeweigerd("https://2130706433/status", INTERNE_HOSTNAAM);
    }

    @Test
    void interneHostnaam_wordtGeweigerd() {
        assertGeweigerd("https://localhost/status", INTERNE_HOSTNAAM);
        assertGeweigerd("https://localhost./status", INTERNE_HOSTNAAM);
        assertGeweigerd("https://LOCALHOST:8443/status", INTERNE_HOSTNAAM);
        assertGeweigerd("https://intranet/status", INTERNE_HOSTNAAM);
        assertGeweigerd("https://foo.localhost/status", INTERNE_HOSTNAAM);
        assertGeweigerd("https://profielservice.moza.svc/status", INTERNE_HOSTNAAM);
        assertGeweigerd("https://profielservice.moza.svc./status", INTERNE_HOSTNAAM);
        assertGeweigerd("https://service.ns.svc.cluster.local/status", INTERNE_HOSTNAAM);
        assertGeweigerd("https://metadata.google.internal/status", INTERNE_HOSTNAAM);
        assertGeweigerd("https://nas.home.arpa/status", INTERNE_HOSTNAAM);
        assertGeweigerd("https://router.home/status", INTERNE_HOSTNAAM);
        assertGeweigerd("https://fileserver.corp/status", INTERNE_HOSTNAAM);
        assertGeweigerd("https://printer.lan/status", INTERNE_HOSTNAAM);
        assertGeweigerd("https://portaal.intranet/status", INTERNE_HOSTNAAM);
    }

    @Test
    void afsluitendePuntInHostnaam_wordtGeaccepteerd() {
        assertGeaccepteerd("https://consument.example.nl./status");
    }

    @Test
    void urlZonderHostnaam_wordtGeweigerd() {
        assertGeweigerd("https:///status", GEEN_HOSTNAAM);
    }

    @Test
    void poortBuitenBereik_wordtGeweigerd() {
        assertGeweigerd("https://consument.example.nl:99999/status", "poort");
    }

    @Test
    void urlPreciesOpDeMaximumlengte_wordtGeaccepteerd() {
        String prefix = "https://consument.example.nl/";
        String url = prefix + "a".repeat(2048 - prefix.length());
        assertEquals(2048, url.length());
        assertGeaccepteerd(url);
    }

    @Test
    void teLangeUrl_wordtGeweigerd() {
        assertGeweigerd("https://consument.example.nl/" + "a".repeat(2048), "langer dan 2048 tekens");
    }

    @Test
    void foutmeldingBenoemtDeWebhookUrl() {
        OngeldigeWebhookUrlException e = assertThrows(OngeldigeWebhookUrlException.class,
                () -> VALIDATOR.valideer(URI.create("http://consument.example.nl/status")));
        assertTrue(e.getMessage().contains("webhook-URL"));
    }

    @Test
    void controleer_geldigeUrl_geeftHemGenormaliseerdTerug() {
        assertEquals("https://dv.example.nl/webhook", VALIDATOR.controleer("HTTPS://dv.example.nl/webhook"));
    }

    @Test
    void controleer_interneOfOnveiligeUrl_weigert() {
        assertThrows(OngeldigeWebhookUrlException.class, () -> VALIDATOR.controleer("http://dv.example.nl/webhook"));
        assertThrows(OngeldigeWebhookUrlException.class, () -> VALIDATOR.controleer("https://10.0.0.1/webhook"));
    }

    @Test
    void controleer_nietTeOntleden_weigert() {
        assertThrows(OngeldigeWebhookUrlException.class, () -> VALIDATOR.controleer("https://dv example.nl/webhook"));
    }

    private static void assertGeaccepteerd(String url) {
        assertDoesNotThrow(() -> VALIDATOR.valideer(URI.create(url)),
                url + " zou geaccepteerd moeten worden");
    }

    /** Asserts both the rejection and the reden, so a case cannot pass via another regel. */
    private static void assertGeweigerd(String url, String reden) {
        OngeldigeWebhookUrlException e = assertThrows(OngeldigeWebhookUrlException.class,
                () -> VALIDATOR.valideer(URI.create(url)),
                url + " zou geweigerd moeten worden");
        assertTrue(e.getMessage().contains(reden),
                url + " werd geweigerd om een andere reden: " + e.getMessage());
    }
}
