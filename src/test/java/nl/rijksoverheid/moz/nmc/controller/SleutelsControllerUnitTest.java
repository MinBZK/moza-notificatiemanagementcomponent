package nl.rijksoverheid.moz.nmc.controller;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.api.model.JsonWebKey;
import nl.rijksoverheid.moz.nmc.api.model.JsonWebKeySet;
import nl.rijksoverheid.moz.nmc.config.WebhookConfig;
import nl.rijksoverheid.moz.nmc.service.webhook.WebhookSleutel;
import nl.rijksoverheid.moz.nmc.testhelper.TestConfig;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Toetst de JWKS tijdens een sleutelwissel zonder Quarkus: de extra sleutel staat alleen in de
 * configuratie, en een andere configuratie vraagt in een {@code @QuarkusTest} een herstart.
 */
class SleutelsControllerUnitTest {

    @Test
    void sleutelsOpvragen_tijdensEenRotatie_publiceertBeideSleutelsEnDeMaxAge() throws Exception {
        WebhookConfig config = TestConfig.webhookJwtMetExtraSleutel(privePem(rsa()), "sleutel-1", publiekePem(rsa()),
                "sleutel-2", Duration.ofMinutes(10));
        SleutelsController controller = new SleutelsController(new WebhookSleutel(Clock.systemUTC(), config), config);

        Response antwoord = controller.sleutelsOpvragen();

        List<JsonWebKey> sleutels = ((JsonWebKeySet) antwoord.getEntity()).getKeys();
        assertEquals(List.of("sleutel-1", "sleutel-2"), sleutels.stream().map(JsonWebKey::getKid).toList());
        assertEquals("public, max-age=600", antwoord.getHeaderString(HttpHeaders.CACHE_CONTROL));
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);

        return generator.generateKeyPair();
    }

    private static String privePem(KeyPair paar) {
        return Base64.getEncoder().encodeToString(paar.getPrivate().getEncoded());
    }

    private static String publiekePem(KeyPair paar) {
        return Base64.getEncoder().encodeToString(paar.getPublic().getEncoded());
    }
}
