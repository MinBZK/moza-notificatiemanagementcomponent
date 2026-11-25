package nl.rijksoverheid.moz.nmc;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;

/** Maakt per testrun een RSA-sleutel en sleutel-id voor de webhook-JWT, zodat er geen sleutel in de repository staat. */
public class WebhookSleutelTestResource implements QuarkusTestResourceLifecycleManager {

    public static final String KEY_ID = "test-webhook-sleutel-niet-voor-productie";

    @Override
    public Map<String, String> start() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            byte[] pkcs8 = generator.generateKeyPair().getPrivate().getEncoded();

            return Map.of(
                    "nmc.webhook.jwt.private-key", Base64.getEncoder().encodeToString(pkcs8),
                    "nmc.webhook.jwt.key-id", KEY_ID);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void stop() {
    }
}
