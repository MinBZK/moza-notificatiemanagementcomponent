package nl.rijksoverheid.moz.nmc.client.notifynl;

import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;

@ApplicationScoped
public class NotifyNLJwtFactory {

    private static final int API_KEY_MIN_LENGTH = 74;
    private static final int SERVICE_ID_START_OFFSET = 73;
    private static final int SERVICE_ID_LENGTH = 36;
    private static final int SECRET_LENGTH = 36;

    public String authorizationHeader(String apiKey) {
        // Geen van deze drie checks impliceert een van de andere (bv. een blanco key kan >= MIN_LENGTH zijn).
        if (apiKey == null || apiKey.isBlank() || apiKey.contains(" ") || apiKey.length() < API_KEY_MIN_LENGTH) {
            throw new IllegalArgumentException("Ongeldige NotifyNL API-key geconfigureerd");
        }

        int keyLength = apiKey.length();
        String serviceId = apiKey.substring(keyLength - SERVICE_ID_START_OFFSET, keyLength - SERVICE_ID_START_OFFSET + SERVICE_ID_LENGTH);
        String secret = apiKey.substring(keyLength - SECRET_LENGTH);

        String token = Jwt.issuer(serviceId)
                .issuedAt(Instant.now())
                .signWithSecret(secret);

        return "Bearer " + token;
    }
}
