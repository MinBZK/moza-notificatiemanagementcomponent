package nl.rijksoverheid.moz.nmc.service;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallbackUrlValidatorTest {

    @Test
    void geldigeHttpsUrl_wordtOngewijzigdTeruggegeven() {
        assertEquals("https://consument.example.nl/status",
                CallbackUrlValidator.valideer(URI.create("https://consument.example.nl/status")));
    }

    @Test
    void poortEnQueryZijnToegestaan() {
        assertEquals("https://consument.example.nl:8443/pad?notificatie=1",
                CallbackUrlValidator.valideer(URI.create("https://consument.example.nl:8443/pad?notificatie=1")));
    }

    @Test
    void hoofdletterSchemeWordtGenormaliseerdNaarKleineLetters() {
        // De uitgaande REST-client vergelijkt het scheme hoofdlettergevoelig, dus "HTTPS"
        // mag niet ongewijzigd worden opgeslagen (anders gaat de callback over plain HTTP).
        assertEquals("https://consument.example.nl/status",
                CallbackUrlValidator.valideer(URI.create("HTTPS://consument.example.nl/status")));
    }

    @Test
    void zonderCallbackUrl_geeftNull() {
        assertNull(CallbackUrlValidator.valideer(null));
    }

    @Test
    void anderSchemaDanHttps_wordtGeweigerd() {
        assertGeweigerd("http://consument.example.nl/status");
        assertGeweigerd("ftp://consument.example.nl/status");
        assertGeweigerd("file:///etc/passwd");
    }

    @Test
    void relatieveUrl_wordtGeweigerd() {
        assertGeweigerd("/alleen-een-pad");
    }

    @Test
    void gebruikersinfo_wordtGeweigerd() {
        assertGeweigerd("https://gebruiker:wachtwoord@consument.example.nl/status");
    }

    @Test
    void ipAdres_wordtGeweigerd() {
        assertGeweigerd("https://127.0.0.1/status");
        assertGeweigerd("https://10.0.0.1:8080/status");
        assertGeweigerd("https://127.1/status");
        assertGeweigerd("https://169.254.169.254/latest/meta-data/");
        assertGeweigerd("https://[::1]/status");
        assertGeweigerd("https://[fd00::1]/status");
    }

    @Test
    void interneHostnaam_wordtGeweigerd() {
        assertGeweigerd("https://localhost/status");
        assertGeweigerd("https://localhost./status");
        assertGeweigerd("https://LOCALHOST:8443/status");
        assertGeweigerd("https://intranet/status");
        assertGeweigerd("https://foo.localhost/status");
        assertGeweigerd("https://profielservice.moza.svc/status");
        assertGeweigerd("https://service.ns.svc.cluster.local/status");
        assertGeweigerd("https://metadata.google.internal/status");
    }

    @Test
    void urlZonderHostnaam_wordtGeweigerd() {
        assertGeweigerd("https:///status");
    }

    @Test
    void poortBuitenBereik_wordtGeweigerd() {
        assertGeweigerd("https://consument.example.nl:99999/status");
    }

    @Test
    void urlPreciesOpDeMaximumlengte_wordtGeaccepteerd() {
        String prefix = "https://consument.example.nl/";
        String url = prefix + "a".repeat(2048 - prefix.length());
        assertEquals(2048, url.length());
        assertEquals(url, CallbackUrlValidator.valideer(URI.create(url)));
    }

    @Test
    void teLangeUrl_wordtGeweigerd() {
        assertGeweigerd("https://consument.example.nl/" + "a".repeat(2048));
    }

    @Test
    void foutmeldingBenoemtDeCallbackUrl() {
        OngeldigeCallbackUrlException e = assertThrows(OngeldigeCallbackUrlException.class,
                () -> CallbackUrlValidator.valideer(URI.create("http://consument.example.nl/status")));
        assertTrue(e.getMessage().contains("callbackUrl"));
    }

    private static void assertGeweigerd(String url) {
        assertThrows(OngeldigeCallbackUrlException.class, () -> CallbackUrlValidator.valideer(URI.create(url)),
                url + " zou geweigerd moeten worden");
    }
}
