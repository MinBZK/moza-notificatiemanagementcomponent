package nl.rijksoverheid.moz.nmc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.rijksoverheid.moz.nmc.domain.VersleuteldeGegevens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleutelbeheerTest {

    static final String KEK_1 = "I8JrSCkAfhntSWtJadWGZeT4SxH4Ld1x3N+aTWU4PGQ=";
    static final String KEK_2 = "PAQwq3xsGw0w/Tbk4r61A2Yz+Cr049JCHzU8in/aWWg=";
    private static final String ANDERE_KEK = "Hgud2BX0iUHDJV+H61LDJbcumEimJdYPbi4haW/3JFQ=";

    private final Sleutelbeheer sleutelbeheer = sleutelbeheer(1, Map.of(1, KEK_1));

    static Sleutelbeheer sleutelbeheer(int huidigeVersie, Map<Integer, String> keks) {
        Map<String, String> config = new HashMap<>();
        config.put(ConfigKekProvider.HUIDIGE_VERSIE, String.valueOf(huidigeVersie));
        keks.forEach((versie, kek) -> config.put(ConfigKekProvider.VERSIE_PREFIX + versie, kek));

        return new Sleutelbeheer(new ConfigKekProvider(naam -> Optional.ofNullable(config.get(naam))), new ObjectMapper());
    }

    @Test
    void ontsleutelOntvanger_naVersleutelen_levertOorspronkelijkAdresOp() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel("burger@example.nl", Map.of());

        assertEquals("burger@example.nl", sleutelbeheer.ontsleutelOntvanger(gegevens));
        assertEquals(1, gegevens.kekVersie());
    }

    static Stream<Arguments> personalisations() {
        return Stream.of(
                Arguments.of(Map.of()),
                Arguments.of(Map.of("naam", "Voorbeeld BV")),
                Arguments.of(Map.of("naam", "Voorbeeld BV", "zaak", "Parkeervergunning", "bedrag", "€ 12,50")));
    }

    @ParameterizedTest
    @MethodSource("personalisations")
    void ontsleutelPersonalisation_naVersleutelen_levertOorspronkelijkeMapOp(Map<String, String> personalisation) {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel("burger@example.nl", personalisation);

        assertEquals(personalisation, sleutelbeheer.ontsleutelPersonalisation(gegevens));
    }

    @Test
    void ontsleutelPersonalisation_nullVersleuteld_levertLegeMapOp() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel("burger@example.nl", null);

        assertEquals(Map.of(), sleutelbeheer.ontsleutelPersonalisation(gegevens));
    }

    @Test
    void versleutel_tweeNotificaties_krijgenElkEenEigenSleutelEnIv() {
        VersleuteldeGegevens eerste = sleutelbeheer.versleutel("burger@example.nl", Map.of("naam", "Voorbeeld BV"));
        VersleuteldeGegevens tweede = sleutelbeheer.versleutel("burger@example.nl", Map.of("naam", "Voorbeeld BV"));

        assertFalse(Arrays.equals(sleutelbeheer.pakUit(eerste).getEncoded(), sleutelbeheer.pakUit(tweede).getEncoded()));
        assertFalse(Arrays.equals(iv(eerste.ontvangerVersleuteld()), iv(tweede.ontvangerVersleuteld())));
        assertFalse(Arrays.equals(iv(eerste.personalisationVersleuteld()), iv(tweede.personalisationVersleuteld())));
        assertFalse(Arrays.equals(eerste.ontvangerVersleuteld(), tweede.ontvangerVersleuteld()));
    }

    @Test
    void versleutel_ontvangerEnPersonalisation_krijgenElkEenEigenIv() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel("burger@example.nl", Map.of());

        assertFalse(Arrays.equals(iv(gegevens.ontvangerVersleuteld()), iv(gegevens.personalisationVersleuteld())));
    }

    @Test
    void versleutel_ontvangerNull_gooitNullPointerException() {
        assertThrows(NullPointerException.class, () -> sleutelbeheer.versleutel(null, Map.of()));
    }

    @Test
    void ontsleutelOntvanger_gewijzigdeCiphertext_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel("burger@example.nl", Map.of());
        byte[] ciphertext = gegevens.ontvangerVersleuteld().clone();
        ciphertext[ciphertext.length - 1] ^= 1;

        OntsleutelenMisluktException fout = assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelOntvanger(metOntvanger(gegevens, ciphertext)));
        assertFalse(fout instanceof SleutelGewistException);
    }

    @Test
    void ontsleutelOntvanger_gewijzigdeIv_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel("burger@example.nl", Map.of());
        byte[] ciphertext = gegevens.ontvangerVersleuteld().clone();
        ciphertext[0] ^= 1;

        assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelOntvanger(metOntvanger(gegevens, ciphertext)));
    }

    @Test
    void ontsleutelOntvanger_verwisseldMetPersonalisation_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel("burger@example.nl", Map.of());

        assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelOntvanger(metOntvanger(gegevens, gegevens.personalisationVersleuteld())));
    }

    @Test
    void ontsleutelOntvanger_ciphertextVanAndereNotificatie_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens eerste = sleutelbeheer.versleutel("burger@example.nl", Map.of());
        VersleuteldeGegevens tweede = sleutelbeheer.versleutel("ander@example.nl", Map.of());

        assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelOntvanger(metOntvanger(eerste, tweede.ontvangerVersleuteld())));
    }

    @Test
    void ontsleutelOntvanger_teKorteOfOntbrekendeCiphertext_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel("burger@example.nl", Map.of());

        assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelOntvanger(metOntvanger(gegevens, new byte[27])));
        assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelOntvanger(metOntvanger(gegevens, null)));
    }

    @Test
    void ontsleutel_gewisteSleutel_gooitSleutelGewistException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel("burger@example.nl", Map.of("naam", "Voorbeeld BV"));
        VersleuteldeGegevens gewist = new VersleuteldeGegevens(gegevens.ontvangerVersleuteld(),
                gegevens.personalisationVersleuteld(), null, gegevens.kekVersie());

        assertThrows(SleutelGewistException.class, () -> sleutelbeheer.ontsleutelOntvanger(gewist));
        assertThrows(SleutelGewistException.class, () -> sleutelbeheer.ontsleutelPersonalisation(gewist));
    }

    @Test
    void ontsleutel_sleutelZonderKekVersie_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel("burger@example.nl", Map.of());
        VersleuteldeGegevens zonderVersie = new VersleuteldeGegevens(gegevens.ontvangerVersleuteld(),
                gegevens.personalisationVersleuteld(), gegevens.sleutelGewrapt(), null);

        assertThrows(OntsleutelenMisluktException.class, () -> sleutelbeheer.ontsleutelOntvanger(zonderVersie));
    }

    @Test
    void ontsleutel_onbekendeKekVersie_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel("burger@example.nl", Map.of());
        VersleuteldeGegevens versie7 = new VersleuteldeGegevens(gegevens.ontvangerVersleuteld(),
                gegevens.personalisationVersleuteld(), gegevens.sleutelGewrapt(), 7);

        OntsleutelenMisluktException fout = assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelOntvanger(versie7));
        assertTrue(fout.getMessage().contains("7"));
    }

    @Test
    void ontsleutel_oudeKekVersieNietMeerGeconfigureerd_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel("burger@example.nl", Map.of());
        Sleutelbeheer alleenVersie2 = sleutelbeheer(2, Map.of(2, KEK_2));

        assertThrows(OntsleutelenMisluktException.class, () -> alleenVersie2.ontsleutelOntvanger(gegevens));
    }

    @Test
    void ontsleutel_sleutelGewraptMetAndereKek_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel("burger@example.nl", Map.of());
        Sleutelbeheer andereKek = sleutelbeheer(1, Map.of(1, ANDERE_KEK));

        OntsleutelenMisluktException fout = assertThrows(OntsleutelenMisluktException.class,
                () -> andereKek.ontsleutelOntvanger(gegevens));
        assertNotNull(fout.getCause());
    }

    @Test
    void ontsleutel_naKekRotatie_ontsleuteltOudeEnNieuweVersie() {
        VersleuteldeGegevens oud = sleutelbeheer.versleutel("burger@example.nl", Map.of("naam", "Oud BV"));
        Sleutelbeheer naRotatie = sleutelbeheer(2, Map.of(1, KEK_1, 2, KEK_2));
        VersleuteldeGegevens nieuw = naRotatie.versleutel("ander@example.nl", Map.of("naam", "Nieuw BV"));

        assertEquals(2, nieuw.kekVersie());
        assertEquals("burger@example.nl", naRotatie.ontsleutelOntvanger(oud));
        assertEquals(Map.of("naam", "Oud BV"), naRotatie.ontsleutelPersonalisation(oud));
        assertEquals("ander@example.nl", naRotatie.ontsleutelOntvanger(nieuw));
    }

    @Test
    void ontsleutelPersonalisation_geenJsonObject_gooitOntsleutelenMisluktException() {
        // Een personalisation-veld dat met dezelfde sleutel en veldnaam een JSON-array bevat: kan alleen
        // ontstaan door een fout buiten Sleutelbeheer, maar mag geen Jackson-exception doorlaten.
        Sleutelbeheer lijstSerialiserend = new Sleutelbeheer(kekProvider(), new ObjectMapper() {
            @Override
            public byte[] writeValueAsBytes(Object value) {
                return "[1]".getBytes(StandardCharsets.UTF_8);
            }
        });
        VersleuteldeGegevens gegevens = lijstSerialiserend.versleutel("burger@example.nl", Map.of());

        OntsleutelenMisluktException fout = assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelPersonalisation(gegevens));
        assertInstanceOf(IOException.class, fout.getCause());
    }

    @Test
    void versleutel_ciphertextBevatIvEnTag() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel("a@b.nl", Map.of());

        assertEquals(12 + "a@b.nl".length() + 16, gegevens.ontvangerVersleuteld().length);
        assertEquals(12 + 32 + 16, gegevens.sleutelGewrapt().length);
    }

    @Test
    void wrap_zelfdeSleutelTweeKeer_levertVerschillendeWrapsDieBeideUitpakken() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey sleutel = generator.generateKey();

        byte[] eerste = sleutelbeheer.wrap(sleutel, 1);
        byte[] tweede = sleutelbeheer.wrap(sleutel, 1);

        assertFalse(Arrays.equals(eerste, tweede));
        assertArrayEquals(sleutel.getEncoded(), sleutelbeheer.pakUit(new VersleuteldeGegevens(null, null, eerste, 1)).getEncoded());
        assertArrayEquals(sleutel.getEncoded(), sleutelbeheer.pakUit(new VersleuteldeGegevens(null, null, tweede, 1)).getEncoded());
    }

    @Test
    void ontsleutel_gewijzigdeGewrapteSleutel_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel("burger@example.nl", Map.of());
        byte[] gewrapt = gegevens.sleutelGewrapt().clone();
        gewrapt[gewrapt.length - 1] ^= 1;

        OntsleutelenMisluktException fout = assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelOntvanger(metSleutel(gegevens, gewrapt, 1)));
        assertFalse(fout instanceof SleutelGewistException);
    }

    @Test
    void ontsleutel_teKorteGewrapteSleutel_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel("burger@example.nl", Map.of());

        assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelOntvanger(metSleutel(gegevens, new byte[27], 1)));
    }

    @Test
    void ontsleutel_gewraptOnderVersie1AlsVersie2Aangeboden_gooitOntsleutelenMisluktException() {
        Sleutelbeheer tweeVersies = sleutelbeheer(1, Map.of(1, KEK_1, 2, KEK_2));
        VersleuteldeGegevens gegevens = tweeVersies.versleutel("burger@example.nl", Map.of());

        assertThrows(OntsleutelenMisluktException.class,
                () -> tweeVersies.ontsleutelOntvanger(metSleutel(gegevens, gegevens.sleutelGewrapt(), 2)));
    }

    @Test
    void ontsleutel_zelfdeKekOnderAndereVersie_gooitOntsleutelenMisluktException() {
        Sleutelbeheer zelfdeKek = sleutelbeheer(1, Map.of(1, KEK_1, 2, KEK_1));
        VersleuteldeGegevens gegevens = zelfdeKek.versleutel("burger@example.nl", Map.of());

        assertThrows(OntsleutelenMisluktException.class,
                () -> zelfdeKek.ontsleutelOntvanger(metSleutel(gegevens, gegevens.sleutelGewrapt(), 2)));
    }

    @Test
    void ontsleutel_gewrapteSleutelAlsVeldAangeboden_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel("burger@example.nl", Map.of());

        assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelOntvanger(metOntvanger(gegevens, gegevens.sleutelGewrapt())));
    }

    private static KekProvider kekProvider() {
        return new ConfigKekProvider(naam -> switch (naam) {
            case ConfigKekProvider.HUIDIGE_VERSIE -> Optional.of("1");
            case ConfigKekProvider.VERSIE_PREFIX + "1" -> Optional.of(KEK_1);
            default -> Optional.empty();
        });
    }

    private static byte[] iv(byte[] ciphertext) {
        return Arrays.copyOf(ciphertext, 12);
    }

    private static VersleuteldeGegevens metOntvanger(VersleuteldeGegevens gegevens, byte[] ontvanger) {
        return new VersleuteldeGegevens(ontvanger, gegevens.personalisationVersleuteld(),
                gegevens.sleutelGewrapt(), gegevens.kekVersie());
    }

    private static VersleuteldeGegevens metSleutel(VersleuteldeGegevens gegevens, byte[] gewrapt, int kekVersie) {
        return new VersleuteldeGegevens(gegevens.ontvangerVersleuteld(), gegevens.personalisationVersleuteld(),
                gewrapt, kekVersie);
    }
}
