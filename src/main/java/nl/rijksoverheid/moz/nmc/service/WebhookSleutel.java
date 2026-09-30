package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.runtime.Startup;
import io.smallrye.jwt.algorithm.SignatureAlgorithm;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * De RSA-sleutel waarmee het NMC de bearer-JWT op elke webhook-aanroep ondertekent (RS256), en de
 * publieke helft daarvan voor de JWKS. Eager bij het opstarten, zodat een ontbrekende of ongeldige
 * sleutel de applicatie niet laat starten.
 */
@Startup
@ApplicationScoped
public class WebhookSleutel {

    private static final int MIN_BITS = 2048;

    private final RSAPrivateCrtKey privateSleutel;
    private final String keyId;
    private final String issuer;
    private final Duration geldigheid;

    public WebhookSleutel(@ConfigProperty(name = "nmc.webhook.jwt.private-key") Optional<String> pem,
                          @ConfigProperty(name = "nmc.webhook.jwt.key-id") Optional<String> keyId,
                          @ConfigProperty(name = "nmc.webhook.jwt.issuer") String issuer,
                          @ConfigProperty(name = "nmc.webhook.jwt.geldigheid") Duration geldigheid) {
        this.privateSleutel = leesSleutel(pem.filter(p -> !p.isBlank())
                .orElseThrow(() -> new IllegalStateException("nmc.webhook.jwt.private-key ontbreekt")));
        this.keyId = keyId.map(String::strip).filter(k -> !k.isEmpty())
                .orElseThrow(() -> new IllegalStateException("nmc.webhook.jwt.key-id ontbreekt"));

        if (geldigheid.isNegative() || geldigheid.isZero()) {
            throw new IllegalStateException("nmc.webhook.jwt.geldigheid moet positief zijn, is " + geldigheid);
        }

        this.issuer = issuer;
        this.geldigheid = geldigheid;
    }

    /** Een vers ondertekende JWT met de webhook-URL als {@code aud}. */
    public String onderteken(String webhookUrl) {
        Instant nu = Instant.now();

        return Jwt.issuer(issuer)
                .audience(webhookUrl)
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
