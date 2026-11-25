package nl.rijksoverheid.moz.nmc.service.webhook;

import nl.rijksoverheid.moz.nmc.testhelper.TestConfig;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.jose4j.jws.JsonWebSignature;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSleutelTest {

    private static final String URL = "https://dv.example.nl/webhook";

    @Test
    void onderteken_levertEenJwtDieMetDeGepubliceerdeSleutelTeControlerenIs() throws Exception {
        KeyPair paar = rsa(2048);
        WebhookSleutel sleutel = sleutel(pem(paar), "sleutel-1");

        WebhookSleutel.PubliekeSleutel gepubliceerd = sleutel.publiekeSleutels().getFirst();
        RSAPublicKey publiek = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(getal(gepubliceerd.modulus()), getal(gepubliceerd.exponent())));
        assertEquals(paar.getPublic(), publiek);

        RsaJsonWebKey jwk = new RsaJsonWebKey(publiek);
        JwtClaims claims = new JwtConsumerBuilder()
                .setVerificationKey(jwk.getPublicKey())
                .setExpectedIssuer("https://nmc.example.nl")
                .setExpectedAudience(URL)
                .build()
                .processToClaims(sleutel.onderteken(URL, "hash-van-de-body", "cursor-1"));
        assertEquals(120, claims.getExpirationTime().getValue() - claims.getIssuedAt().getValue());
        assertEquals("hash-van-de-body", claims.getStringClaimValue(WebhookSleutel.BODY_HASH_CLAIM));
        assertEquals("cursor-1", claims.getStringClaimValue(WebhookSleutel.CURSOR_CLAIM));
        assertEquals("sleutel-1", gepubliceerd.keyId());
    }

    // Een ontvanger kiest de sleutel uit de JWKS op de kid in de header van de JWT.
    @Test
    void onderteken_zetDeKidEnHetAlgoritmeInDeHeader() throws Exception {
        WebhookSleutel sleutel = sleutel(pem(rsa(2048)), "sleutel-1");
        JsonWebSignature jws = new JsonWebSignature();

        jws.setCompactSerialization(sleutel.onderteken(URL, "hash-van-de-body", "cursor-1"));

        assertEquals("sleutel-1", jws.getKeyIdHeaderValue());
        assertEquals("RS256", jws.getAlgorithmHeaderValue());
    }

    // Aan de jti herkent een ontvanger een token dat voor de tweede keer wordt aangeboden.
    @Test
    void onderteken_geeftElkeTokenEenEigenJti() throws Exception {
        WebhookSleutel sleutel = sleutel(pem(rsa(2048)), "sleutel-1");

        String eerste = jti(sleutel.onderteken(URL, "hash-van-de-body", "cursor-1"));
        String tweede = jti(sleutel.onderteken(URL, "hash-van-de-body", "cursor-1"));

        assertDoesNotThrow(() -> UUID.fromString(eerste));
        assertNotEquals(eerste, tweede);
    }

    private static String jti(String token) throws Exception {
        return new JwtConsumerBuilder().setSkipAllValidators().setDisableRequireSignature().setSkipSignatureVerification()
                .build().processToClaims(token).getJwtId();
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

    @Test
    void publiekeSleutels_zonderExtraSleutel_bevatAlleenDeEigenSleutel() throws Exception {
        KeyPair paar = rsa(2048);
        WebhookSleutel sleutel = sleutel(pem(paar), "sleutel-1");

        List<WebhookSleutel.PubliekeSleutel> sleutels = sleutel.publiekeSleutels();

        assertEquals(1, sleutels.size());
        assertEquals("sleutel-1", sleutels.getFirst().keyId());
        assertEquals(((RSAPublicKey) paar.getPublic()).getModulus(), getal(sleutels.getFirst().modulus()));
        assertEquals(((RSAPublicKey) paar.getPublic()).getPublicExponent(), getal(sleutels.getFirst().exponent()));
    }

    // Lege waarden uit een env-var tellen als afwezig.
    @Test
    void publiekeSleutels_extraSleutelAlleenWitruimte_bevatAlleenDeEigenSleutel() throws Exception {
        WebhookSleutel sleutel = metExtraSleutel(pem(rsa(2048)), "sleutel-1", " ", " ");

        assertEquals(1, sleutel.publiekeSleutels().size());
    }

    @Test
    void publiekeSleutels_metExtraSleutel_bevatBeideMetHunEigenKid() throws Exception {
        KeyPair eigen = rsa(2048);
        KeyPair extra = rsa(2048);
        WebhookSleutel sleutel = metExtraSleutel(pem(eigen), "sleutel-1", publiekePem(extra), " sleutel-2 ");

        List<WebhookSleutel.PubliekeSleutel> sleutels = sleutel.publiekeSleutels();

        assertEquals(List.of("sleutel-1", "sleutel-2"), sleutels.stream().map(WebhookSleutel.PubliekeSleutel::keyId).toList());
        assertEquals(((RSAPublicKey) eigen.getPublic()).getModulus(), getal(sleutels.get(0).modulus()));
        assertEquals(((RSAPublicKey) extra.getPublic()).getModulus(), getal(sleutels.get(1).modulus()));
        assertEquals(((RSAPublicKey) extra.getPublic()).getPublicExponent(), getal(sleutels.get(1).exponent()));
    }

    @Test
    void extraSleutel_zonderIdOfIdZonderSleutel_weigert() throws Exception {
        String eigen = pem(rsa(2048));
        String extra = publiekePem(rsa(2048));

        assertThrows(IllegalStateException.class, () -> metExtraSleutel(eigen, "sleutel-1", extra, null));
        assertThrows(IllegalStateException.class, () -> metExtraSleutel(eigen, "sleutel-1", null, "sleutel-2"));
    }

    // Twee sleutels onder dezelfde kid: een ontvanger weet dan niet welke hij moet nemen.
    @Test
    void extraSleutel_metDeKidVanDeEigenSleutel_weigert() throws Exception {
        String eigen = pem(rsa(2048));
        String extra = publiekePem(rsa(2048));

        IllegalStateException fout = assertThrows(IllegalStateException.class,
                () -> metExtraSleutel(eigen, "sleutel-1", extra, "sleutel-1"));

        assertTrue(fout.getMessage().contains("extra-key-id"));
    }

    // Bij een wissel is dan vergeten de extra sleutel om te zetten: de oude kid wijst naar de nieuwe sleutel.
    @Test
    void extraSleutel_dezelfdeSleutelAlsDeEigenSleutel_weigert() throws Exception {
        KeyPair paar = rsa(2048);

        IllegalStateException fout = assertThrows(IllegalStateException.class,
                () -> metExtraSleutel(pem(paar), "sleutel-2", publiekePem(paar), "sleutel-1"));

        assertTrue(fout.getMessage().contains("dezelfde sleutel"));
    }

    @Test
    void leesPubliekeSleutel_zonderKopEnVoetOfRegelafbrekingen_wordtGeaccepteerd() throws Exception {
        String pem = publiekePem(rsa(2048));
        String kaal = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");

        assertEquals(WebhookSleutel.leesPubliekeSleutel(pem).getModulus(), WebhookSleutel.leesPubliekeSleutel(kaal).getModulus());
    }

    @Test
    void leesPubliekeSleutel_ongeldigeInhoud_weigert() throws Exception {
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(256);
        String ecPem = Base64.getEncoder().encodeToString(ec.generateKeyPair().getPublic().getEncoded());
        String priveSleutel = pem(rsa(2048));

        assertThrows(IllegalStateException.class, () -> WebhookSleutel.leesPubliekeSleutel("geen base64 !"));
        assertThrows(IllegalStateException.class, () -> WebhookSleutel.leesPubliekeSleutel("AAAA"));
        assertThrows(IllegalStateException.class, () -> WebhookSleutel.leesPubliekeSleutel(ecPem));
        assertThrows(IllegalStateException.class, () -> WebhookSleutel.leesPubliekeSleutel(priveSleutel));
        IllegalStateException kort = assertThrows(IllegalStateException.class,
                () -> WebhookSleutel.leesPubliekeSleutel(publiekePem(rsa(1024))));
        assertTrue(kort.getMessage().contains("2048"));
    }

    private static WebhookSleutel metExtraSleutel(String pem, String keyId, String extraPem, String extraKeyId) {
        return new WebhookSleutel(Clock.systemUTC(),
                TestConfig.webhookJwtMetExtraSleutel(pem, keyId, extraPem, extraKeyId, Duration.ofHours(1)));
    }

    private static String publiekePem(KeyPair paar) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(paar.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
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
