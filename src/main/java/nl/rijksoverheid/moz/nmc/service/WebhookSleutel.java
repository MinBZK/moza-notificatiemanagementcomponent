package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.runtime.Startup;
import io.smallrye.jwt.algorithm.SignatureAlgorithm;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.config.WebhookConfig;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/**
 * De RSA-sleutel waarmee het NMC de bearer-JWT op elke webhook-aanroep ondertekent (RS256), en de
 * publieke helft daarvan voor de JWKS. Eager bij het opstarten, zodat een ontbrekende of ongeldige
 * sleutel de applicatie niet laat starten.
 */
@Startup
@ApplicationScoped
public class WebhookSleutel {

    private static final int MIN_BITS = 2048;

    /** De claim met de SHA-256 van de body (base64url), die token en inhoud aan elkaar bindt. */
    static final String BODY_HASH_CLAIM = "body_sha256";

    private final RSAPrivateCrtKey privateSleutel;
    private final String keyId;
    private final String issuer;
    private final Duration geldigheid;
    private final Clock clock;

    public WebhookSleutel(Clock clock, WebhookConfig config) {
        WebhookConfig.Jwt jwt = config.jwt();

        this.privateSleutel = leesSleutel(jwt.privateKey());
        this.keyId = jwt.keyId().strip();
        this.issuer = jwt.issuer();
        this.geldigheid = jwt.geldigheid();
        this.clock = clock;
    }

    /**
     * Een vers ondertekende JWT met de webhook-URL als {@code aud} en de hash van de body als
     * {@code body_sha256}, zodat een onderschepte token niet met een andere body te gebruiken is.
     */
    String onderteken(String webhookUrl, String bodyHash) {
        Instant nu = clock.instant();

        return Jwt.issuer(issuer)
                .audience(webhookUrl)
                .claim(BODY_HASH_CLAIM, bodyHash)
                .issuedAt(nu)
                .expiresAt(nu.plus(geldigheid))
                .jws()
                .keyId(keyId)
                .algorithm(SignatureAlgorithm.RS256)
                .sign(privateSleutel);
    }

    public String keyId() {
        return keyId;
    }

    /** De modulus van de publieke sleutel, base64url zonder voorloopnul zoals JWK die vraagt. */
    public String modulus() {
        return base64url(privateSleutel.getModulus());
    }

    public String publiekeExponent() {
        return base64url(privateSleutel.getPublicExponent());
    }

    // PKCS#8 als PEM, met of zonder kop- en voetregel en regelafbrekingen; een env-var kan de PEM
    // dan ook op één regel dragen.
    static RSAPrivateCrtKey leesSleutel(String pem) {
        String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");

        try {
            byte[] der = Base64.getDecoder().decode(base64);

            if (!(KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der)) instanceof RSAPrivateCrtKey sleutel)) {
                throw new IllegalStateException("nmc.webhook.jwt.private-key bevat geen volledige RSA-sleutel");
            }

            if (sleutel.getModulus().bitLength() < MIN_BITS) {
                throw new IllegalStateException("nmc.webhook.jwt.private-key moet minstens " + MIN_BITS + " bits zijn");
            }

            return sleutel;
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalStateException("nmc.webhook.jwt.private-key is geen RSA-sleutel in PKCS#8-PEM", e);
        }
    }

    private static String base64url(BigInteger getal) {
        byte[] bytes = getal.toByteArray();

        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }

        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
