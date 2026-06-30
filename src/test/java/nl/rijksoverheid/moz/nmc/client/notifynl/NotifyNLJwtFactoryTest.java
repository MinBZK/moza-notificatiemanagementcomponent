package nl.rijksoverheid.moz.nmc.client.notifynl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NotifyNLJwtFactoryTest {

    private final NotifyNLJwtFactory factory = new NotifyNLJwtFactory();

    // "some-service" (12) + "-" (1) + serviceId (36) + "-" (1) + secret (36) = 86 chars
    private static final String GELDIGE_API_KEY =
            "some-service-12345678-1234-1234-1234-123456789012-abcdefab-abcd-abcd-abcd-abcdefabcdef";

    @Test
    void authorizationHeader_geldigeApiKey_retourneertBearerToken() {
        String result = factory.authorizationHeader(GELDIGE_API_KEY);

        assertTrue(result.startsWith("Bearer "));
    }

    @Test
    void authorizationHeader_nullApiKey_gooitException() {
        assertThrows(IllegalArgumentException.class, () -> factory.authorizationHeader(null));
    }

    @Test
    void authorizationHeader_legeApiKey_gooitException() {
        assertThrows(IllegalArgumentException.class, () -> factory.authorizationHeader("   "));
    }

    @Test
    void authorizationHeader_apiKeyMetSpatie_gooitException() {
        assertThrows(IllegalArgumentException.class, () -> factory.authorizationHeader("ongeldige key"));
    }

    @Test
    void authorizationHeader_teKorteApiKey_gooitException() {
        assertThrows(IllegalArgumentException.class, () -> factory.authorizationHeader("te-kort"));
    }
}
