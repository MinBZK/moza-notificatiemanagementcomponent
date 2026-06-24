package nl.rijksoverheid.moz.nmc.helper;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;

@ApplicationScoped
public class HashHelper {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecretKeySpec pepperKey;

    public HashHelper(@ConfigProperty(name = "hash.pepper") Optional<String> pepper) {
        String pepperValue = pepper.filter(s -> !s.isBlank())
                .orElseThrow(() -> new IllegalStateException("hash.pepper is niet geconfigureerd"));
        this.pepperKey = new SecretKeySpec(pepperValue.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    // Keyed HMAC met een geheime pepper, zodat een pseudonym niet via brute force (terug) naar
    // het oorspronkelijke BSN/KVK/RSIN te herleiden is door iemand met enkel toegang tot de logs.
    // Deterministisch (zelfde input + pepper -> zelfde pseudonym) — een per-call salt zou dat
    // onmogelijk maken, terwijl auditpseudonyms net consistent moeten zijn over loginvoer heen.
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
