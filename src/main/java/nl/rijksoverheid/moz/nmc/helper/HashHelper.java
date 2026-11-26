package nl.rijksoverheid.moz.nmc.helper;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class HashHelper {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** De versie van de pepper waarmee {@link #hmac} rekent; staat bij elke bewaarde HMAC op de rij. */
    public static final short PEPPER_VERSIE = 1;

    private final SecretKeySpec pepperKey;

    public HashHelper(@ConfigProperty(name = "hash.pepper") Optional<String> pepper) {
        String pepperValue = pepper.filter(s -> !s.isBlank())
                .orElseThrow(() -> new IllegalStateException("hash.pepper is niet geconfigureerd"));
        this.pepperKey = new SecretKeySpec(pepperValue.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    /**
     * HMAC-SHA-256 met een sleutel per doel, afgeleid uit de pepper. Dezelfde waarde geeft voor een ander
     * doel dus een andere uitkomst, zodat HMAC's voor verschillende doelen niet te koppelen zijn.
     *
     * @param doel   waarvoor de HMAC dient, een vaste naam
     * @param waarde de waarde om te hashen
     */
    public byte[] hmac(String doel, String waarde) {
        try {
            Mac afleiding = Mac.getInstance(HMAC_ALGORITHM);
            afleiding.init(pepperKey);
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(afleiding.doFinal(doel.getBytes(StandardCharsets.UTF_8)), HMAC_ALGORITHM));

            return mac.doFinal(waarde.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 kon niet worden geïnitialiseerd", e);
        }
    }

    public String hashIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(pepperKey);
            byte[] hash = mac.doFinal(identifier.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HmacSHA256 kon niet worden geïnitialiseerd", e);
        }
    }
}
