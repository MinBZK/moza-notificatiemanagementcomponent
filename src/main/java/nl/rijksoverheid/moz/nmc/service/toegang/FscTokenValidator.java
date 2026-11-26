package nl.rijksoverheid.moz.nmc.service.toegang;

import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.config.FscConfig;
import org.jose4j.http.Get;
import org.jose4j.jwa.AlgorithmConstraints.ConstraintType;
import org.jose4j.jwk.HttpsJwks;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.NumericDate;
import org.jose4j.jwt.consumer.ErrorCodes;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.keys.resolvers.HttpsJwksVerificationKeyResolver;
import org.jose4j.lang.JoseException;
import org.jose4j.lang.UnresolvableKeyException;

import java.io.IOException;
import java.time.Clock;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Controleert een FSC-toegangstoken en levert het OIN van de dienstverlener uit {@code sub}. De
 * handtekening wordt getoetst aan de JWKS van de Manager die het token uitgaf, en het token moet
 * voor de eigen inway, groep en dienst zijn uitgegeven. De binding aan het certificaat van de
 * aanroeper ({@code cnf}) kan alleen de inway toetsen: een aanroep die de inway omzeilt, wordt hier
 * op het token alleen beoordeeld.
 */
@ApplicationScoped
public class FscTokenValidator {

    /** De naam van de TLS-configuratie ({@code quarkus.tls.fsc.*}) voor het ophalen van de JWKS. */
    static final String TLS_CONFIGURATIE = "fsc";

    private static final String CLAIM_GROEP = "gid";
    private static final String CLAIM_DIENST = "svc";
    private static final String CLAIM_NAMENS = "act";

    // De algoritmen die FSC voor het toegangstoken toestaat.
    private static final String[] ALGORITMEN = {
            AlgorithmIdentifiers.RSA_USING_SHA256, AlgorithmIdentifiers.RSA_USING_SHA384, AlgorithmIdentifiers.RSA_USING_SHA512,
            AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256, AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384,
            AlgorithmIdentifiers.ECDSA_USING_P521_CURVE_AND_SHA512};

    // Een token met een onbekende sleutel laat de JWKS opnieuw ophalen; vaker dan dit gebeurt dat niet,
    // ook niet als het ophalen mislukt.
    private static final long MINIMUM_TUSSEN_OPHALEN_MS = 10_000;

    private final FscConfig config;
    private final Clock clock;
    private final BegrensdeJwksGet ophaler;
    private final HttpsJwks jwks;
    private final HttpsJwksVerificationKeyResolver sleutels;

    @Inject
    public FscTokenValidator(FscConfig config, Clock clock, TlsConfigurationRegistry tls) {
        this(config, clock, tls, MINIMUM_TUSSEN_OPHALEN_MS);
    }

    // Package-private, zodat een test de wachttijd tussen twee ophalingen kan kiezen.
    FscTokenValidator(FscConfig config, Clock clock, TlsConfigurationRegistry tls, long minimumTussenOphalenMs) {
        this.config = config;
        this.clock = clock;

        if (!config.ingeschakeld()) {
            this.ophaler = null;
            this.jwks = null;
            this.sleutels = null;

            return;
        }

        this.ophaler = new BegrensdeJwksGet(get(config, tls.get(TLS_CONFIGURATIE)), clock, minimumTussenOphalenMs);
        this.jwks = new HttpsJwks(config.jwksUrl().orElseThrow().toString());
        jwks.setSimpleHttpGet(ophaler);
        // De ophaler geeft de bewaartijd van de Manager niet door, dus geldt altijd deze.
        jwks.setDefaultCacheDuration(config.jwksBewaartijd().toSeconds());
        jwks.setRetainCacheOnErrorDuration(config.jwksStoringstolerantie().toSeconds());
        jwks.setRefreshReprieveThreshold(minimumTussenOphalenMs);
        this.sleutels = new HttpsJwksVerificationKeyResolver(jwks);
        // Het token wijst zijn sleutel aan met x5t#S256. Draagt de JWKS die duim niet, dan passen
        // meerdere sleutels en beslist de handtekening.
        sleutels.setDisambiguateWithVerifySignature(true);
    }

    /** Of aanroepen een toegangstoken moeten dragen ({@code nmc.fsc.ingeschakeld}). */
    public boolean ingeschakeld() {
        return sleutels != null;
    }

    /**
     * @param token het toegangstoken in compacte vorm, zonder {@code Bearer}
     * @return de {@code sub} van het token: het OIN van de dienstverlener
     * @throws OngeldigFscTokenException        als het token niet geldig is; de boodschap is voor de log,
     *                                          niet voor de aanroeper
     * @throws FscSleutelsOnbereikbaarException als de JWKS niet op te halen of geen JWKS is, en er geen
     *                                          sleutels zijn bewaard of de storing langer duurt dan
     *                                          {@code nmc.fsc.jwks-storingstolerantie}
     */
    public String valideer(String token) {
        if (sleutels == null) {
            throw new IllegalStateException("De controle van het FSC-toegangstoken staat uit (nmc.fsc.ingeschakeld)");
        }

        eisSleutelsBinnenDeStoringstolerantie();

        JwtClaims claims;
        try {
            claims = new JwtConsumerBuilder()
                    .setVerificationKeyResolver(sleutels)
                    .setJwsAlgorithmConstraints(ConstraintType.PERMIT, ALGORITMEN)
                    .setExpectedIssuer(config.uitgever().orElseThrow())
                    // Een token voor een andere inway, zoals die van een andere omgeving met dezelfde
                    // groep en dienstnaam, geldt hier niet.
                    .setExpectedAudience(config.inwayAdres().orElseThrow())
                    .setRequireSubject()
                    .setRequireExpirationTime()
                    .setRequireNotBefore()
                    .setAllowedClockSkewInSeconds((int) config.klokmarge().toSeconds())
                    .setEvaluationTime(NumericDate.fromMilliseconds(clock.millis()))
                    .build()
                    .processToClaims(token);
        } catch (InvalidJwtException e) {
            throw vertaal(e);
        }

        eisClaim(claims, CLAIM_GROEP, config.groep().orElseThrow());
        eisClaim(claims, CLAIM_DIENST, config.dienst().orElseThrow());

        // Bij een gedelegeerde verbinding is sub de opdrachtgever en act de aanroeper. Het NMC kent alleen
        // dienstverleners die op eigen titel aanroepen.
        if (claims.hasClaim(CLAIM_NAMENS)) {
            throw new OngeldigFscTokenException("het token is uitgegeven voor een gedelegeerde verbinding (act)");
        }

        try {
            return claims.getSubject();
        } catch (MalformedClaimException e) {
            throw new OngeldigFscTokenException("sub is geen tekst");
        }
    }

    // jose4j verlengt de bewaarde sleutels bij elke mislukte ophaling opnieuw; de grens op de duur van
    // een storing staat daarom hier. Een sleutel die de Manager heeft ingetrokken, blijft anders
    // geldig zolang de Manager onbereikbaar is.
    private void eisSleutelsBinnenDeStoringstolerantie() {
        if (!ophaler.storingLangerDan(config.jwksStoringstolerantie())) {
            return;
        }

        String boodschap = "De JWKS van de Manager is langer dan nmc.fsc.jwks-storingstolerantie niet op te halen";

        try {
            jwks.refresh();
        } catch (JoseException | IOException e) {
            throw new FscSleutelsOnbereikbaarException(boodschap, e);
        }

        // jose4j slaat een ophaling kort na de vorige over; dan is er niets ververst.
        if (ophaler.storingLangerDan(config.jwksStoringstolerantie())) {
            throw new FscSleutelsOnbereikbaarException(boodschap);
        }
    }

    private static void eisClaim(JwtClaims claims, String naam, String verwacht) {
        if (!verwacht.equals(claims.getClaimValue(naam))) {
            throw new OngeldigFscTokenException(naam + " is niet de verwachte waarde " + verwacht);
        }
    }

    // De boodschappen van jose4j kunnen het hele token bevatten; de log krijgt alleen de afgekeurde punten.
    private static RuntimeException vertaal(InvalidJwtException e) {
        for (Throwable oorzaak = e.getCause(); oorzaak != null; oorzaak = oorzaak.getCause()) {
            // Het zoeken van de sleutel faalt ook op een token met een onbekend of niet toegestaan
            // algoritme; alleen een fout bij het ophalen zelf betekent dat de JWKS onbereikbaar is.
            if (oorzaak instanceof UnresolvableKeyException && oorzaak.getCause() instanceof IOException) {
                return new FscSleutelsOnbereikbaarException("De JWKS van de Manager is niet op te halen", oorzaak.getCause());
            }

            if (oorzaak instanceof UnresolvableKeyException) {
                return new OngeldigFscTokenException("de JWKS heeft geen bruikbare sleutel voor dit token");
            }
        }

        String reden = e.getErrorDetails().stream()
                .map(fout -> omschrijving(fout.getErrorCode()))
                .distinct()
                .collect(Collectors.joining(", "));

        return new OngeldigFscTokenException(reden.isEmpty() ? "het token is niet te ontleden" : reden);
    }

    private static String omschrijving(int foutcode) {
        return switch (foutcode) {
            case ErrorCodes.EXPIRED -> "verlopen";
            case ErrorCodes.NOT_YET_VALID -> "nog niet geldig";
            case ErrorCodes.EXPIRATION_MISSING -> "exp ontbreekt";
            case ErrorCodes.NOT_BEFORE_MISSING -> "nbf ontbreekt";
            case ErrorCodes.SUBJECT_MISSING -> "sub ontbreekt";
            case ErrorCodes.ISSUER_MISSING -> "iss ontbreekt";
            case ErrorCodes.ISSUER_INVALID -> "iss is niet de verwachte uitgever";
            case ErrorCodes.AUDIENCE_MISSING -> "aud ontbreekt";
            case ErrorCodes.AUDIENCE_INVALID -> "aud is niet het adres van de eigen inway";
            case ErrorCodes.SIGNATURE_INVALID -> "de handtekening klopt niet";
            case ErrorCodes.SIGNATURE_MISSING -> "niet ondertekend";
            default -> "het token is niet te ontleden of niet toegestaan (jose4j-foutcode " + foutcode + ")";
        };
    }

    private static Get get(FscConfig config, Optional<TlsConfiguration> tls) {
        Get get = new Get();
        // De time-out geldt voor het verbinden en voor elke leesactie, niet voor de hele ophaling, en een
        // mislukte poging wordt één keer herhaald; aanroepen van dienstverleners die de sleutels nodig
        // hebben, wachten daarop.
        get.setRetries(1);
        get.setConnectTimeout((int) config.jwksTimeout().toMillis());
        get.setReadTimeout((int) config.jwksTimeout().toMillis());
        tls.ifPresent(configuratie -> {
            try {
                get.setSslSocketFactory(configuratie.createSSLContext().getSocketFactory());
            } catch (Exception e) {
                throw new IllegalStateException("quarkus.tls." + TLS_CONFIGURATIE + " is niet bruikbaar", e);
            }
        });

        return get;
    }
}
