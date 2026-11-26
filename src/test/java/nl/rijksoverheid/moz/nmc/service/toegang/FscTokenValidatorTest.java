package nl.rijksoverheid.moz.nmc.service.toegang;

import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import nl.rijksoverheid.moz.nmc.config.FscConfig;
import nl.rijksoverheid.moz.nmc.testhelper.FscManagerStub;
import nl.rijksoverheid.moz.nmc.testhelper.FscManagerStub.Sleutel;
import nl.rijksoverheid.moz.nmc.testhelper.TestConfig;
import nl.rijksoverheid.moz.nmc.testhelper.VerstelbareClock;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.keys.HmacKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FscTokenValidatorTest {

    private static final Instant NU = Instant.parse("2026-11-26T10:00:00Z");
    private static final Clock KLOK = Clock.fixed(NU, ZoneOffset.UTC);
    private static final String OIN = "00000001823288444000";
    // De klokmarge uit TestConfig.fsc.
    private static final long KLOKMARGE = 30;

    private FscManagerStub manager;
    private Sleutel sleutel;
    private FscTokenValidator validator;

    @BeforeEach
    void setUp() {
        manager = new FscManagerStub();
        sleutel = Sleutel.rsa("duim-1");
        manager.publiceer(sleutel);
        validator = validator(0);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void valideer_geldigToken_geeftHetOinUitSub() {
        assertEquals(OIN, validator.valideer(sleutel.token(claims())));
    }

    @Test
    void valideer_geldigTokenMetEcSleutel_geeftHetOin() {
        Sleutel ec = Sleutel.ec("duim-ec");
        manager.publiceer(sleutel, ec);

        assertEquals(OIN, validator.valideer(ec.token(claims())));
    }

    // Niet elke Manager zet de duim bij de sleutel; dan passen alle sleutels en beslist de handtekening.
    @Test
    void valideer_jwksZonderDuimMetMeerdereSleutels_kiestOpDeHandtekening() {
        Sleutel tweede = Sleutel.rsa("duim-2");
        manager.publiceer(sleutel.zonderDuimInJwks(), tweede.zonderDuimInJwks());

        assertEquals(OIN, validator.valideer(tweede.token(claims())));
        assertEquals(OIN, validator.valideer(sleutel.token(claims())));
    }

    @Test
    void valideer_ondertekendMetEenAndereSleutelOnderDezelfdeDuim_wordtGeweigerd() {
        assertThrows(OngeldigFscTokenException.class, () -> validator.valideer(sleutel.vervalsing().token(claims())));
    }

    @Test
    void valideer_sleutelDieDeJwksNietKent_wordtGeweigerd() {
        Sleutel onbekend = Sleutel.rsa("duim-onbekend");

        assertThrows(OngeldigFscTokenException.class, () -> validator.valideer(onbekend.token(claims())));
    }

    @Test
    void valideer_nieuweSleutelNaEenSleutelwissel_haaltDeJwksOpnieuwOp() {
        validator.valideer(sleutel.token(claims()));
        Sleutel nieuw = Sleutel.rsa("duim-2");
        manager.publiceer(sleutel, nieuw);

        assertEquals(OIN, validator.valideer(nieuw.token(claims())));
        assertEquals(2, manager.aanroepen());
    }

    // Anders laat iedereen met een verzonnen sleutel de Manager bij elke aanroep bevragen.
    @Test
    void valideer_onbekendeSleutelKortNaHetOphalen_haaltDeJwksNietOpnieuwOp() {
        FscTokenValidator metWachttijd = validator(60_000);
        metWachttijd.valideer(sleutel.token(claims()));
        Sleutel onbekend = Sleutel.rsa("duim-onbekend");

        assertThrows(OngeldigFscTokenException.class, () -> metWachttijd.valideer(onbekend.token(claims())));
        assertThrows(OngeldigFscTokenException.class, () -> metWachttijd.valideer(onbekend.token(claims())));
        assertEquals(1, manager.aanroepen());
    }

    @ParameterizedTest
    @CsvSource({"-30, true", "-31, false"})
    void valideer_verlopenToken_isGeldigTotEnMetDeKlokmarge(long expTenOpzichteVanNu, boolean geldig) {
        JwtClaims claims = claims();
        claims.setExpirationTime(NumericDate.fromSeconds(NU.getEpochSecond() + expTenOpzichteVanNu + 1));

        assertEquals(geldig, isGeldig(sleutel.token(claims)), "klokmarge " + KLOKMARGE + " s");
    }

    @ParameterizedTest
    @CsvSource({"30, true", "31, false"})
    void valideer_tokenDatNogNietGeldigIs_isGeldigVanafDeKlokmarge(long nbfTenOpzichteVanNu, boolean geldig) {
        JwtClaims claims = claims();
        claims.setNotBefore(NumericDate.fromSeconds(NU.getEpochSecond() + nbfTenOpzichteVanNu));

        assertEquals(geldig, isGeldig(sleutel.token(claims)), "klokmarge " + KLOKMARGE + " s");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sub", "exp", "nbf", "iss", "aud", "gid", "svc"})
    void valideer_verplichteClaimOntbreekt_wordtGeweigerd(String claim) {
        JwtClaims claims = claims();
        claims.unsetClaim(claim);

        assertThrows(OngeldigFscTokenException.class, () -> validator.valideer(sleutel.token(claims)));
    }

    // Een token van dezelfde Manager voor een andere dienst, groep of inway, of van een andere uitgever.
    @ParameterizedTest
    @CsvSource({"iss, 00000000000000100000", "aud, https://inway.acceptatie.example.nl:443", "gid, andere-groep",
            "svc, profiel-service"})
    void valideer_claimMetEenAndereWaarde_wordtGeweigerd(String claim, String waarde) {
        JwtClaims claims = claims();
        claims.setStringClaim(claim, waarde);

        assertThrows(OngeldigFscTokenException.class, () -> validator.valideer(sleutel.token(claims)));
    }

    @Test
    void valideer_tokenVoorEenGedelegeerdeVerbinding_wordtGeweigerd() {
        JwtClaims claims = claims();
        claims.setClaim("act", Map.of("sub", "00000000000000200000"));

        OngeldigFscTokenException fout = assertThrows(OngeldigFscTokenException.class,
                () -> validator.valideer(sleutel.token(claims)));

        assertTrue(fout.getMessage().contains("act"), fout.getMessage());
    }

    @Test
    void valideer_tokenZonderHandtekening_wordtGeweigerd() throws Exception {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims().toJson());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.NONE);
        jws.setAlgorithmConstraints(org.jose4j.jwa.AlgorithmConstraints.NO_CONSTRAINTS);
        String token = jws.getCompactSerialization();

        assertThrows(OngeldigFscTokenException.class, () -> validator.valideer(token));
    }

    // De publieke sleutel is bekend; als HMAC-geheim mag hij geen geldig token opleveren.
    @Test
    void valideer_hmacMetDePubliekeSleutelAlsGeheim_wordtGeweigerd() throws Exception {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims().toJson());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.HMAC_SHA256);
        jws.setKey(new HmacKey(sleutel.paar().getPublic().getEncoded()));
        jws.setX509CertSha256ThumbprintHeaderValue(sleutel.duim());
        String token = jws.getCompactSerialization();

        assertThrows(OngeldigFscTokenException.class, () -> validator.valideer(token));
    }

    // Het zoeken van de sleutel struikelt over zo'n token voordat de handtekening aan bod komt; dat is
    // een afgekeurd token en geen storing bij de Manager.
    @ParameterizedTest
    @ValueSource(strings = {"XYZ", "PS256"})
    void valideer_onbekendOfNietToegestaanAlgoritme_wordtGeweigerdEnIsGeenStoring(String algoritme) {
        manager.publiceer(sleutel.zonderDuimInJwks(), Sleutel.rsa("duim-2").zonderDuimInJwks());
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"alg\":\"" + algoritme + "\"}").getBytes(StandardCharsets.UTF_8));
        String geldig = sleutel.token(claims());
        String token = header + geldig.substring(geldig.indexOf('.'));

        assertThrows(OngeldigFscTokenException.class, () -> validator.valideer(token));
    }

    // De handtekening klopt en de sleutel staat in de JWKS; alleen de lijst met toegestane algoritmen
    // houdt dit token tegen.
    @Test
    void valideer_geldigeHandtekeningMetEenNietToegestaanAlgoritme_wordtGeweigerd() {
        Sleutel pss = new Sleutel(sleutel.paar(), AlgorithmIdentifiers.RSA_PSS_USING_SHA256, sleutel.duim(), true);

        assertThrows(OngeldigFscTokenException.class, () -> validator.valideer(pss.token(claims())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"geen-token", "a.b.c", "", "eyJhbGciOiJSUzI1NiJ9.e30."})
    void valideer_rommel_wordtGeweigerdZonderHetTokenInDeBoodschap(String token) {
        OngeldigFscTokenException fout = assertThrows(OngeldigFscTokenException.class, () -> validator.valideer(token));

        assertFalse(fout.getMessage().isBlank());
        assertFalse(!token.isEmpty() && fout.getMessage().contains(token), fout.getMessage());
    }

    @Test
    void valideer_afgekeurdToken_noemtHetTokenNietInDeBoodschap() {
        JwtClaims claims = claims();
        claims.setExpirationTime(NumericDate.fromSeconds(NU.getEpochSecond() - 3600));
        String token = sleutel.token(claims);

        OngeldigFscTokenException fout = assertThrows(OngeldigFscTokenException.class, () -> validator.valideer(token));

        assertFalse(fout.getMessage().contains(token.substring(token.lastIndexOf('.') + 1)), fout.getMessage());
    }

    @Test
    void valideer_jwksNooitBereikbaarGeweest_gooitSleutelsOnbereikbaar() {
        manager.zetStoring(true);

        assertThrows(FscSleutelsOnbereikbaarException.class, () -> validator.valideer(sleutel.token(claims())));
    }

    @Test
    void valideer_managerNietBereikbaar_gooitSleutelsOnbereikbaar() {
        String token = sleutel.token(claims());
        manager.close();

        assertThrows(FscSleutelsOnbereikbaarException.class, () -> validator.valideer(token));
    }

    // Anders wacht elke aanroep tijdens een storing op een eigen ophaling, de een na de ander.
    @Test
    void valideer_storingZonderBewaardeSleutels_haaltBinnenDeWachttijdNietOpnieuwOp() {
        FscTokenValidator metWachttijd = validator(60_000);
        manager.zetStoring(true);
        String token = sleutel.token(claims());

        assertThrows(FscSleutelsOnbereikbaarException.class, () -> metWachttijd.valideer(token));
        int naDeEerste = manager.aanroepen();
        assertThrows(FscSleutelsOnbereikbaarException.class, () -> metWachttijd.valideer(token));

        assertTrue(naDeEerste > 0);
        assertEquals(naDeEerste, manager.aanroepen());
    }

    @Test
    void valideer_storingZonderBewaardeSleutels_haaltNaDeWachttijdOpnieuwOp() {
        manager.zetStoring(true);
        String token = sleutel.token(claims());

        assertThrows(FscSleutelsOnbereikbaarException.class, () -> validator.valideer(token));
        int naDeEerste = manager.aanroepen();
        manager.zetStoring(false);

        assertEquals(OIN, validator.valideer(token));
        assertEquals(naDeEerste + 1, manager.aanroepen());
    }

    // Een lege sleutelset is voor jose4j bij elk token een reden om opnieuw op te halen.
    @Test
    void valideer_jwksZonderSleutels_haaltBinnenDeWachttijdEenKeerOp() {
        FscTokenValidator metWachttijd = validator(60_000);
        manager.publiceer();
        String token = sleutel.token(claims());

        assertThrows(OngeldigFscTokenException.class, () -> metWachttijd.valideer(token));
        assertThrows(OngeldigFscTokenException.class, () -> metWachttijd.valideer(token));
        assertEquals(1, manager.aanroepen());
    }

    // Een verkeerd adres of een foutpagina van een proxy: de dienstverlener kan het later opnieuw
    // proberen, zijn token is niet afgekeurd.
    @ParameterizedTest
    @ValueSource(strings = {"<html><body>Inloggen</body></html>", "{}", "[]", ""})
    void valideer_antwoordDatGeenJwksIs_gooitSleutelsOnbereikbaar(String body) {
        manager.publiceerRuw(body);

        assertThrows(FscSleutelsOnbereikbaarException.class, () -> validator.valideer(sleutel.token(claims())));
    }

    // Een storing bij de Manager houdt dienstverleners met een geldig token niet buiten; alleen een
    // token met een sleutel die opnieuw ophalen vraagt, is dan niet te controleren.
    @Test
    void valideer_storingNaEerderOphalen_gebruiktDeBewaardeSleutels() {
        validator.valideer(sleutel.token(claims()));
        manager.zetStoring(true);
        Sleutel onbekend = Sleutel.rsa("duim-onbekend");

        assertThrows(FscSleutelsOnbereikbaarException.class, () -> validator.valideer(onbekend.token(claims())));
        assertEquals(OIN, validator.valideer(sleutel.token(claims())));
    }

    // De storingstolerantie uit TestConfig.fsc is een uur. Een sleutel die de Manager intussen heeft
    // ingetrokken, mag niet geldig blijven zolang de Manager onbereikbaar is.
    @ParameterizedTest
    @CsvSource({"3600, true", "3601, false"})
    void valideer_storingMetBewaardeSleutels_gebruiktZeTotEnMetDeStoringstolerantie(long secondenStoring, boolean geldig) {
        VerstelbareClock klok = new VerstelbareClock(NU);
        FscTokenValidator metKlok = validator(klok, 0);
        beginStoringMetBewaardeSleutels(metKlok);

        klok.zet(NU.plusSeconds(secondenStoring));
        String token = sleutel.token(FscManagerStub.claims(OIN, klok.instant()));

        if (geldig) {
            assertEquals(OIN, metKlok.valideer(token));
        } else {
            assertThrows(FscSleutelsOnbereikbaarException.class, () -> metKlok.valideer(token));
        }
    }

    @Test
    void valideer_managerWeerBereikbaarNaDeStoringstolerantie_controleertWeer() {
        VerstelbareClock klok = new VerstelbareClock(NU);
        FscTokenValidator metKlok = validator(klok, 0);
        beginStoringMetBewaardeSleutels(metKlok);
        klok.zet(NU.plusSeconds(3601));
        String token = sleutel.token(FscManagerStub.claims(OIN, klok.instant()));
        assertThrows(FscSleutelsOnbereikbaarException.class, () -> metKlok.valideer(token));

        manager.zetStoring(false);
        int voorHetHerstel = manager.aanroepen();

        assertEquals(OIN, metKlok.valideer(token));
        assertEquals(OIN, metKlok.valideer(token));
        assertEquals(voorHetHerstel + 1, manager.aanroepen());
    }

    // jose4j slaat een verversing over als het de sleutels korter dan de wachttijd geleden heeft bewaard,
    // ook na een mislukte verversing. Het rekent dat en de bewaartijd op de systeemklok, vandaar het
    // wachten.
    @Test
    void valideer_storingstolerantieVerstrekenEnVerversingOvergeslagen_gooitSleutelsOnbereikbaar() throws InterruptedException {
        VerstelbareClock klok = new VerstelbareClock(NU);
        FscTokenValidator metKlok = validator(klok, 2000, Duration.ofSeconds(1));
        metKlok.valideer(sleutel.token(claims()));
        manager.zetStoring(true);
        Thread.sleep(2100);
        klok.zet(NU.plusSeconds(2));
        // De bewaartijd is verstreken en het verversen mislukt: hier begint de storing.
        assertEquals(OIN, metKlok.valideer(sleutel.token(FscManagerStub.claims(OIN, klok.instant()))));
        int naDeMislukteVerversing = manager.aanroepen();

        klok.zet(NU.plusSeconds(2 + 3601));
        String token = sleutel.token(FscManagerStub.claims(OIN, klok.instant()));

        assertThrows(FscSleutelsOnbereikbaarException.class, () -> metKlok.valideer(token));
        assertTrue(naDeMislukteVerversing > 1, "de verversing na de bewaartijd is geprobeerd");
        assertEquals(naDeMislukteVerversing, manager.aanroepen(), "de verversing na de storingstolerantie is overgeslagen");
    }

    // jose4j ververst na de bewaartijd; mislukt dat, dan blijven de oude sleutels in gebruik. jose4j
    // rekent die bewaartijd op de systeemklok, vandaar het wachten.
    @Test
    void valideer_verversenMislukt_gebruiktDeBewaardeSleutels() throws InterruptedException {
        FscTokenValidator korteBewaartijd = validator(KLOK, 0, Duration.ofSeconds(1));
        String token = sleutel.token(claims());
        korteBewaartijd.valideer(token);
        manager.zetStoring(true);
        Thread.sleep(1500);

        assertEquals(OIN, korteBewaartijd.valideer(token));
        assertTrue(manager.aanroepen() > 1, "de bewaartijd is verstreken en er is opnieuw opgehaald");
    }

    // Een sleutel die de Manager intrekt, blijft anders geldig zo lang als de Manager of een proxy
    // ervoor opgeeft. jose4j rekent de bewaartijd op de systeemklok, vandaar het wachten.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"Cache-Control | public, max-age=604800", "Expires | Thu, 31 Dec 2099 23:59:59 GMT"})
    void valideer_managerGeeftEenLangereBewaartijdOp_haaltNaDeEigenBewaartijdOpnieuwOp(String header, String waarde)
            throws InterruptedException {
        if (header.equals("Expires")) {
            manager.zetExpires(waarde);
        } else {
            manager.zetCacheControl(waarde);
        }

        FscTokenValidator korteBewaartijd = validator(KLOK, 0, Duration.ofSeconds(1));
        String token = sleutel.token(claims());
        korteBewaartijd.valideer(token);
        manager.publiceer(Sleutel.rsa("duim-2"));
        Thread.sleep(1500);

        assertThrows(OngeldigFscTokenException.class, () -> korteBewaartijd.valideer(token));
    }

    @Test
    void valideer_binnenDeBewaartijd_haaltDeJwksNietOpnieuwOp() {
        String token = sleutel.token(claims());
        validator.valideer(token);
        manager.publiceer(Sleutel.rsa("duim-2"));

        assertEquals(OIN, validator.valideer(token));
        assertEquals(1, manager.aanroepen());
    }

    @Test
    void valideer_metTlsConfiguratie_haaltDeJwksOpMetDieConfiguratie() throws Exception {
        TellendeSocketFactory sockets = new TellendeSocketFactory();
        SSLContext context = mock(SSLContext.class);
        when(context.getSocketFactory()).thenReturn(sockets);
        TlsConfiguration configuratie = mock(TlsConfiguration.class);
        when(configuratie.createSSLContext()).thenReturn(context);
        FscTokenValidator metTls = new FscTokenValidator(
                TestConfig.fsc(URI.create("https://127.0.0.1:1/.well-known/jwks.json")), KLOK, tls(Optional.of(configuratie)), 0);

        assertThrows(FscSleutelsOnbereikbaarException.class, () -> metTls.valideer(sleutel.token(claims())));
        assertTrue(sockets.gevraagd.get() > 0, "de verbinding is opgezet met de socketfactory uit quarkus.tls.fsc");
    }

    @Test
    void constructor_tlsConfiguratieNietBruikbaar_weigertMetDeNaamVanDeConfiguratie() throws Exception {
        TlsConfiguration configuratie = mock(TlsConfiguration.class);
        when(configuratie.createSSLContext()).thenThrow(new IllegalArgumentException("geen truststore"));
        FscConfig config = TestConfig.fsc(manager.jwksUrl());

        IllegalStateException fout = assertThrows(IllegalStateException.class,
                () -> new FscTokenValidator(config, KLOK, tls(Optional.of(configuratie)), 0));

        assertTrue(fout.getMessage().contains("quarkus.tls.fsc"), fout.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"exp, exp ontbreekt", "nbf, nbf ontbreekt", "sub, sub ontbreekt", "iss, iss ontbreekt",
            "aud, aud ontbreekt"})
    void valideer_afgekeurdToken_noemtDeRedenVoorDeLog(String claim, String reden) {
        JwtClaims claims = claims();
        claims.unsetClaim(claim);

        OngeldigFscTokenException fout = assertThrows(OngeldigFscTokenException.class,
                () -> validator.valideer(sleutel.token(claims)));

        assertEquals(reden, fout.getMessage());
    }

    @Test
    void valideer_verlopenTokenVanEenAndereUitgever_noemtBeideRedenen() {
        JwtClaims claims = claims();
        claims.setIssuer("00000000000000100000");
        claims.setExpirationTime(NumericDate.fromSeconds(NU.getEpochSecond() - 3600));

        OngeldigFscTokenException fout = assertThrows(OngeldigFscTokenException.class,
                () -> validator.valideer(sleutel.token(claims)));

        assertTrue(fout.getMessage().contains("verlopen"), fout.getMessage());
        assertTrue(fout.getMessage().contains("iss is niet de verwachte uitgever"), fout.getMessage());
    }

    @Test
    void valideer_controleUitgeschakeld_weigertTeValideren() {
        FscTokenValidator uit = new FscTokenValidator(TestConfig.fscUit(), KLOK, geenTls());

        assertFalse(uit.ingeschakeld());
        assertTrue(validator.ingeschakeld());
        assertThrows(IllegalStateException.class, () -> uit.valideer(sleutel.token(claims())));
    }

    private boolean isGeldig(String token) {
        try {
            validator.valideer(token);

            return true;
        } catch (OngeldigFscTokenException e) {
            return false;
        }
    }

    // Vult de bewaarde sleutels en laat daarna een ophaling mislukken, op het tijdstip NU.
    private void beginStoringMetBewaardeSleutels(FscTokenValidator validator) {
        validator.valideer(sleutel.token(claims()));
        manager.zetStoring(true);
        Sleutel onbekend = Sleutel.rsa("duim-onbekend");

        assertThrows(FscSleutelsOnbereikbaarException.class, () -> validator.valideer(onbekend.token(claims())));
    }

    private FscTokenValidator validator(long minimumTussenOphalenMs) {
        return validator(KLOK, minimumTussenOphalenMs);
    }

    private FscTokenValidator validator(Clock klok, long minimumTussenOphalenMs) {
        return validator(klok, minimumTussenOphalenMs, Duration.ofHours(1));
    }

    private FscTokenValidator validator(Clock klok, long minimumTussenOphalenMs, Duration jwksBewaartijd) {
        FscConfig config = TestConfig.fsc(manager.jwksUrl(), jwksBewaartijd);

        return new FscTokenValidator(config, klok, geenTls(), minimumTussenOphalenMs);
    }

    private static TlsConfigurationRegistry geenTls() {
        return tls(Optional.empty());
    }

    private static TlsConfigurationRegistry tls(Optional<TlsConfiguration> configuratie) {
        TlsConfigurationRegistry tls = mock(TlsConfigurationRegistry.class);
        when(tls.get(FscTokenValidator.TLS_CONFIGURATIE)).thenReturn(configuratie);

        return tls;
    }

    /** Telt hoe vaak om een socket is gevraagd en weigert er een te geven, zodat er geen verbinding ontstaat. */
    private static final class TellendeSocketFactory extends SSLSocketFactory {

        private final AtomicInteger gevraagd = new AtomicInteger();

        private Socket weiger() throws IOException {
            gevraagd.incrementAndGet();

            throw new IOException("geen socket in deze test");
        }

        @Override
        public Socket createSocket() throws IOException {
            return weiger();
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            return weiger();
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return weiger();
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return weiger();
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return weiger();
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return weiger();
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return new String[0];
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return new String[0];
        }
    }

    private static JwtClaims claims() {
        return FscManagerStub.claims(OIN, NU);
    }
}
