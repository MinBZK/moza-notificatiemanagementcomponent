package nl.rijksoverheid.moz.nmc.service.webhook;

import io.quarkus.runtime.Startup;
import io.smallrye.jwt.algorithm.SignatureAlgorithm;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.config.WebhookConfig;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.jwt.Claims;

/**
 * De RSA-sleutel waarmee het NMC de bearer-JWT op elke webhook-aanroep ondertekent (RS256), en de
 * publieke helft daarvan voor de JWKS. Tijdens een sleutelwissel draagt hij ook een extra publieke
 * sleutel voor de JWKS, waarmee het NMC niet ondertekent. Eager bij het opstarten, zodat een
 * ontbrekende of ongeldige sleutel de applicatie niet laat starten.
 */
@Startup
@ApplicationScoped
public class WebhookSleutel {

    private static final int MIN_BITS = 2048;

    /** De claim met de SHA-256 van de body (base64url), die token en inhoud aan elkaar bindt. */
    static final String BODY_HASH_CLAIM = "body_sha256";

    /** De claim met de cursor uit de header {@code Nmc-Cursor}, zodat de header onder de handtekening valt. */
    static final String CURSOR_CLAIM = "cursor";

    private final RSAPrivateCrtKey privateSleutel;
    private final String keyId;
    private final String issuer;
    private final Duration geldigheid;
    private final Clock clock;
    private final Optional<PubliekeSleutel> extraSleutel;

    public WebhookSleutel(Clock clock, WebhookConfig config) {
        WebhookConfig.Jwt jwt = config.jwt();

        this.privateSleutel = leesSleutel(jwt.privateKey());
        this.keyId = jwt.keyId().strip();
        this.issuer = jwt.issuer();
        this.geldigheid = jwt.geldigheid();
        this.clock = clock;
        this.extraSleutel = leesExtraSleutel(jwt.extraPublicKey(), jwt.extraKeyId(), this.keyId,
                this.privateSleutel.getModulus());
    }

    /**
     * Een publieke sleutel zoals de JWKS hem publiceert.
     *
     * @param keyId    de {@code kid}
     * @param modulus  de modulus, base64url zonder voorloopnul
     * @param exponent de publieke exponent, base64url zonder voorloopnul
     */
    public record PubliekeSleutel(String keyId, String modulus, String exponent) {
    }

    /**
     * De sleutels voor de JWKS: de sleutel waarmee het NMC ondertekent, en tijdens een rotatie de
     * extra sleutel.
     */
    public List<PubliekeSleutel> publiekeSleutels() {
        PubliekeSleutel eigen = new PubliekeSleutel(keyId, base64url(privateSleutel.getModulus()),
                base64url(privateSleutel.getPublicExponent()));

        return extraSleutel.map(extra -> List.of(eigen, extra)).orElseGet(() -> List.of(eigen));
    }

    /**
     * Een vers ondertekende JWT met de webhook-URL als {@code aud}, de hash van de body als
     * {@code body_sha256} en de cursor als {@code cursor}, zodat een onderschepte token niet met een andere
     * body of cursor te gebruiken is, en een eigen {@code jti} per aanroep.
     */
    String onderteken(String webhookUrl, String bodyHash, String cursor) {
        Instant nu = clock.instant();

        return Jwt.issuer(issuer)
                .audience(webhookUrl)
                .claim(Claims.jti.name(), UUID.randomUUID().toString())
                .claim(BODY_HASH_CLAIM, bodyHash)
                .claim(CURSOR_CLAIM, cursor)
                .issuedAt(nu)
                .expiresAt(nu.plus(geldigheid))
                .jws()
                .keyId(keyId)
                .algorithm(SignatureAlgorithm.RS256)
                .sign(privateSleutel);
    }

    // PKCS#8 als PEM, met of zonder kop- en voetregel en regelafbrekingen; een env-var kan de PEM
    // dan ook op één regel dragen. Package-private, zodat de test de vormen van de PEM los van de
    // configuratie kan toetsen.
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

    // De extra sleutel is er alleen tijdens een rotatie; een halve of dubbele configuratie zou de
    // JWKS stil onbruikbaar maken en laat de applicatie daarom niet starten.
    private static Optional<PubliekeSleutel> leesExtraSleutel(Optional<String> pem, Optional<String> keyId, String eigenKeyId,
                                                              BigInteger eigenModulus) {
        Optional<String> sleutel = pem.filter(waarde -> !waarde.isBlank());
        Optional<String> id = keyId.map(String::strip).filter(waarde -> !waarde.isEmpty());

        if (sleutel.isEmpty() && id.isEmpty()) {
            return Optional.empty();
        }

        if (sleutel.isEmpty() || id.isEmpty()) {
            throw new IllegalStateException(
                    "nmc.webhook.jwt.extra-public-key en nmc.webhook.jwt.extra-key-id horen bij elkaar: zet beide of geen van beide");
        }

        if (id.get().equals(eigenKeyId)) {
            throw new IllegalStateException("nmc.webhook.jwt.extra-key-id is gelijk aan nmc.webhook.jwt.key-id");
        }

        RSAPublicKey publiek = leesPubliekeSleutel(sleutel.get());

        // Dezelfde sleutel onder twee ids: bij een wissel is dan de oude of de nieuwe sleutel vergeten.
        if (publiek.getModulus().equals(eigenModulus)) {
            throw new IllegalStateException(
                    "nmc.webhook.jwt.extra-public-key is dezelfde sleutel als nmc.webhook.jwt.private-key");
        }

        return Optional.of(new PubliekeSleutel(id.get(), base64url(publiek.getModulus()), base64url(publiek.getPublicExponent())));
    }

    // X.509 (SubjectPublicKeyInfo) als PEM, met of zonder kop- en voetregel en regelafbrekingen.
    // Package-private, zodat de test de vormen van de PEM los van de configuratie kan toetsen.
    static RSAPublicKey leesPubliekeSleutel(String pem) {
        String base64 = pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");

        try {
            byte[] der = Base64.getDecoder().decode(base64);
            // Een sleutel van een ander soort weigert de RSA-KeyFactory met een InvalidKeySpecException.
            RSAPublicKey sleutel = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));

            if (sleutel.getModulus().bitLength() < MIN_BITS) {
                throw new IllegalStateException("nmc.webhook.jwt.extra-public-key moet minstens " + MIN_BITS + " bits zijn");
            }

            return sleutel;
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalStateException("nmc.webhook.jwt.extra-public-key is geen publieke RSA-sleutel in X.509-PEM", e);
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
