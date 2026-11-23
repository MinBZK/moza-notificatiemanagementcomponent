package nl.rijksoverheid.moz.nmc.service;

import nl.rijksoverheid.moz.nmc.testhelper.TestConfig;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSleutelTest {

    private static final String URL = "https://dv.example.nl/webhook";

    @Test
    void onderteken_levertEenJwtDieMetDeGepubliceerdeSleutelTeControlerenIs() throws Exception {
        KeyPair paar = rsa(2048);
        WebhookSleutel sleutel = sleutel(pem(paar), "sleutel-1");

        RSAPublicKey publiek = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(getal(sleutel.modulus()), getal(sleutel.publiekeExponent())));
        assertEquals(paar.getPublic(), publiek);

        RsaJsonWebKey jwk = new RsaJsonWebKey(publiek);
        JwtClaims claims = new JwtConsumerBuilder()
                .setVerificationKey(jwk.getPublicKey())
                .setExpectedIssuer("https://nmc.example.nl")
                .setExpectedAudience(URL)
                .build()
                .processToClaims(sleutel.onderteken(URL, "hash-van-de-body"));
        assertEquals(120, claims.getExpirationTime().getValue() - claims.getIssuedAt().getValue());
        assertEquals("hash-van-de-body", claims.getStringClaimValue(WebhookSleutel.BODY_HASH_CLAIM));
        assertEquals("sleutel-1", sleutel.keyId());
    }

    // Een env-var draagt de PEM vaak op één regel, zonder kop en voet.
    @Test
    void leesSleutel_zonderKopEnVoetOfRegelafbrekingen_wordtGeaccepteerd() throws Exception {
        String pem = pem(rsa(2048));
        String kaal = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");

        assertEquals(WebhookSleutel.leesSleutel(pem).getModulus(), WebhookSleutel.leesSleutel(kaal).getModulus());
    }

    @Test
    void leesSleutel_ongeldigeInhoud_weigert() throws Exception {
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(256);
        String ecPem = Base64.getEncoder().encodeToString(ec.generateKeyPair().getPrivate().getEncoded());

        assertThrows(IllegalStateException.class, () -> WebhookSleutel.leesSleutel("geen base64 !"));
        assertThrows(IllegalStateException.class, () -> WebhookSleutel.leesSleutel("AAAA"));
        assertThrows(IllegalStateException.class, () -> WebhookSleutel.leesSleutel(ecPem));
        IllegalStateException kort = assertThrows(IllegalStateException.class,
                () -> WebhookSleutel.leesSleutel(pem(rsa(1024))));
        assertTrue(kort.getMessage().contains("2048"));
    }

    // Een PKCS#8-sleutel zonder CRT-componenten levert de JDK als gewone RSAPrivateKey op.
    @Test
    void leesSleutel_rsaSleutelZonderCrtComponenten_weigert() throws Exception {
        RSAPrivateCrtKey volledig = (RSAPrivateCrtKey) rsa(2048).getPrivate();
        PrivateKey zonderCrt = KeyFactory.getInstance("RSA").generatePrivate(
                new RSAPrivateKeySpec(volledig.getModulus(), volledig.getPrivateExponent()));
        String pem = Base64.getEncoder().encodeToString(zonderCrt.getEncoded());

        IllegalStateException fout = assertThrows(IllegalStateException.class, () -> WebhookSleutel.leesSleutel(pem));
        assertTrue(fout.getMessage().contains("geen volledige RSA-sleutel"));
    }

    // Een exponent van één byte heeft geen voorloopnul die eraf moet.
    @Test
    void publiekeExponent_exponentVanEenByte_blijftOngewijzigd() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(new RSAKeyGenParameterSpec(2048, BigInteger.valueOf(3)));
        WebhookSleutel sleutel = sleutel(pem(generator.generateKeyPair()), "sleutel-1");

        assertEquals("Aw", sleutel.publiekeExponent());
        assertEquals(BigInteger.valueOf(3), getal(sleutel.publiekeExponent()));
    }

    private static WebhookSleutel sleutel(String pem, String keyId) {
        return new WebhookSleutel(Clock.systemUTC(),
                TestConfig.webhookJwt(pem, keyId, "https://nmc.example.nl", Duration.ofMinutes(2)));
    }

    private static KeyPair rsa(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);

        return generator.generateKeyPair();
    }

    private static String pem(KeyPair paar) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(paar.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    private static BigInteger getal(String base64url) {
        return new BigInteger(1, Base64.getUrlDecoder().decode(base64url));
    }
}
