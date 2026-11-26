package nl.rijksoverheid.moz.nmc.testhelper;

import com.sun.net.httpserver.HttpServer;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.PublicJsonWebKey;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.lang.JoseException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Nep-Manager van de FSC-peer die het NMC aanbiedt: publiceert een JWKS op een vrije poort van de
 * eigen machine en ondertekent toegangstokens zoals een Manager ze uitgeeft.
 */
public final class FscManagerStub implements AutoCloseable {

    public static final String UITGEVER = "00000000000000001000";
    public static final String INWAY_ADRES = "https://inway.example.nl:443";
    public static final String GROEP = "fsc-testgroep";
    public static final String DIENST = "nmc";

    private final HttpServer server;
    private final AtomicInteger aanroepen = new AtomicInteger();
    private volatile String jwks = "{\"keys\":[]}";
    private volatile boolean storing;
    private volatile String cacheControl;
    private volatile String expires;

    public FscManagerStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        server.createContext("/.well-known/jwks.json", exchange -> {
            aanroepen.incrementAndGet();
            byte[] body = jwks.getBytes(StandardCharsets.UTF_8);

            if (storing) {
                exchange.sendResponseHeaders(500, -1);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");

                if (cacheControl != null) {
                    exchange.getResponseHeaders().add("Cache-Control", cacheControl);
                }

                if (expires != null) {
                    exchange.getResponseHeaders().add("Expires", expires);
                }

                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }

            exchange.close();
        });
        server.start();
    }

    public URI jwksUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/jwks.json");
    }

    /** Vervangt de JWKS door een body die er geen hoeft te zijn; het antwoord blijft 200. */
    public void publiceerRuw(String body) {
        this.jwks = body;
    }

    /** Vervangt de gepubliceerde sleutels. */
    public void publiceer(Sleutel... sleutels) {
        List<JsonWebKey> jwks = Arrays.stream(sleutels).map(Sleutel::jwk).toList();
        this.jwks = new JsonWebKeySet(jwks).toJson(JsonWebKey.OutputControlLevel.PUBLIC_ONLY);
    }

    /** Geeft de JWKS vanaf nu deze {@code Cache-Control}-header mee. */
    public void zetCacheControl(String cacheControl) {
        this.cacheControl = cacheControl;
    }

    /** Geeft de JWKS vanaf nu deze {@code Expires}-header mee. */
    public void zetExpires(String expires) {
        this.expires = expires;
    }

    /** Laat de JWKS vanaf nu 500 antwoorden, of weer gewoon. */
    public void zetStoring(boolean storing) {
        this.storing = storing;
    }

    /** Hoe vaak de JWKS is opgehaald. */
    public int aanroepen() {
        return aanroepen.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** De claims van een geldig token voor deze dienstverlener, een minuut oud en nog vier minuten geldig. */
    public static JwtClaims claims(String oin, Instant nu) {
        JwtClaims claims = new JwtClaims();
        claims.setIssuer(UITGEVER);
        claims.setSubject(oin);
        claims.setAudience(INWAY_ADRES);
        claims.setStringClaim("gid", GROEP);
        claims.setStringClaim("svc", DIENST);
        claims.setStringClaim("gth", "$1$4$testgranthash");
        claims.setNotBefore(NumericDate.fromSeconds(nu.getEpochSecond() - 60));
        claims.setExpirationTime(NumericDate.fromSeconds(nu.getEpochSecond() + 240));

        return claims;
    }

    /**
     * Een sleutelpaar van de Manager.
     *
     * @param paar      de sleutels
     * @param algoritme het JWS-algoritme waarmee deze sleutel ondertekent
     * @param duim      de waarde van {@code x5t#S256} in de tokenheader
     * @param duimInJwks of de JWKS die duim bij de sleutel vermeldt
     */
    public record Sleutel(KeyPair paar, String algoritme, String duim, boolean duimInJwks) {

        public static Sleutel rsa(String duim) {
            return new Sleutel(genereer("RSA"), AlgorithmIdentifiers.RSA_USING_SHA256, duim, true);
        }

        public static Sleutel ec(String duim) {
            return new Sleutel(genereer("EC"), AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256, duim, true);
        }

        /** De private helft van een RSA-sleutel uit {@link #privaatBase64()}: genoeg om tokens te ondertekenen. */
        public static Sleutel rsaUitPrivaat(String duim, String privaatBase64) {
            try {
                PrivateKey privaat = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privaatBase64)));

                return new Sleutel(new KeyPair(null, privaat), AlgorithmIdentifiers.RSA_USING_SHA256, duim, true);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Dezelfde sleutel, zonder duim in de JWKS. */
        public Sleutel zonderDuimInJwks() {
            return new Sleutel(paar, algoritme, duim, false);
        }

        /** Een andere sleutel die zich met dezelfde duim aandient. */
        public Sleutel vervalsing() {
            return new Sleutel(genereer(paar.getPrivate().getAlgorithm()), algoritme, duim, duimInJwks);
        }

        public String privaatBase64() {
            return Base64.getEncoder().encodeToString(paar.getPrivate().getEncoded());
        }

        /** Een token met deze claims, ondertekend met deze sleutel. */
        public String token(JwtClaims claims) {
            JsonWebSignature jws = new JsonWebSignature();
            jws.setPayload(claims.toJson());
            jws.setKey(paar.getPrivate());
            jws.setAlgorithmHeaderValue(algoritme);
            jws.setX509CertSha256ThumbprintHeaderValue(duim);

            try {
                return jws.getCompactSerialization();
            } catch (JoseException e) {
                throw new IllegalStateException(e);
            }
        }

        private JsonWebKey jwk() {
            try {
                PublicJsonWebKey jwk = PublicJsonWebKey.Factory.newPublicJwk(paar.getPublic());

                if (duimInJwks) {
                    jwk.setX509CertificateSha256Thumbprint(duim);
                }

                return jwk;
            } catch (JoseException e) {
                throw new IllegalStateException(e);
            }
        }

        private static KeyPair genereer(String soort) {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance(soort);

                if (soort.equals("EC")) {
                    generator.initialize(new ECGenParameterSpec("secp256r1"));
                } else {
                    generator.initialize(2048);
                }

                return generator.generateKeyPair();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
