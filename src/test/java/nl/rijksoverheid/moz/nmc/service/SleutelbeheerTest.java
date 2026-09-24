package nl.rijksoverheid.moz.nmc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.rijksoverheid.moz.nmc.domain.Ontvanger;
import nl.rijksoverheid.moz.nmc.domain.VersleuteldeGegevens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleutelbeheerTest {

    private static final UUID ID = UUID.fromString("3f1a2b4c-0000-4000-8000-000000000001");

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
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of());

        assertEquals(Ontvanger.email("burger@example.nl"), sleutelbeheer.ontsleutelOntvanger(ID, gegevens));
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
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel(ID, Ontvanger.email("burger@example.nl"), personalisation);

        assertEquals(personalisation, sleutelbeheer.ontsleutelPersonalisation(ID, gegevens));
    }

    @Test
    void ontsleutelPersonalisation_nullVersleuteld_levertLegeMapOp() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel(ID, Ontvanger.email("burger@example.nl"), null);

        assertEquals(Map.of(), sleutelbeheer.ontsleutelPersonalisation(ID, gegevens));
    }

    @Test
    void versleutel_tweeNotificaties_krijgenElkEenEigenSleutelEnIv() {
        VersleuteldeGegevens eerste = sleutelbeheer.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of("naam", "Voorbeeld BV"));
        VersleuteldeGegevens tweede = sleutelbeheer.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of("naam", "Voorbeeld BV"));

        assertFalse(Arrays.equals(sleutelbeheer.pakUit(ID, eerste).getEncoded(), sleutelbeheer.pakUit(ID, tweede).getEncoded()));
        assertFalse(Arrays.equals(iv(eerste.ontvangerVersleuteld()), iv(tweede.ontvangerVersleuteld())));
        assertFalse(Arrays.equals(iv(eerste.personalisationVersleuteld()), iv(tweede.personalisationVersleuteld())));
        assertFalse(Arrays.equals(eerste.ontvangerVersleuteld(), tweede.ontvangerVersleuteld()));
    }

    @Test
    void versleutel_ontvangerEnPersonalisation_krijgenElkEenEigenIv() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of());

        assertFalse(Arrays.equals(iv(gegevens.ontvangerVersleuteld()), iv(gegevens.personalisationVersleuteld())));
    }

    @Test
    void versleutel_ontvangerNull_gooitNullPointerException() {
        assertThrows(NullPointerException.class, () -> sleutelbeheer.versleutel(ID, null, Map.of()));
    }

    @Test
    void ontsleutelOntvanger_gewijzigdeCiphertext_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of());
        byte[] ciphertext = gegevens.ontvangerVersleuteld().clone();
        ciphertext[ciphertext.length - 1] ^= 1;

        OntsleutelenMisluktException fout = assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelOntvanger(ID, metOntvanger(gegevens, ciphertext)));
        assertFalse(fout instanceof SleutelGewistException);
    }

    @Test
    void ontsleutelOntvanger_gewijzigdeIv_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of());
        byte[] ciphertext = gegevens.ontvangerVersleuteld().clone();
        ciphertext[0] ^= 1;

        assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelOntvanger(ID, metOntvanger(gegevens, ciphertext)));
    }

    @Test
    void ontsleutelOntvanger_verwisseldMetPersonalisation_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of());

        assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelOntvanger(ID, metOntvanger(gegevens, gegevens.personalisationVersleuteld())));
    }

    @Test
    void ontsleutelOntvanger_ciphertextVanAndereNotificatie_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens eerste = sleutelbeheer.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of());
        VersleuteldeGegevens tweede = sleutelbeheer.versleutel(ID, Ontvanger.email("ander@example.nl"), Map.of());

        assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelOntvanger(ID, metOntvanger(eerste, tweede.ontvangerVersleuteld())));
    }

    @Test
    void ontsleutelOntvanger_teKorteOfOntbrekendeCiphertext_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of());

        assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelOntvanger(ID, metOntvanger(gegevens, new byte[27])));
        assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelOntvanger(ID, metOntvanger(gegevens, null)));
    }

    @Test
    void ontsleutel_gewisteSleutel_gooitSleutelGewistException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of("naam", "Voorbeeld BV"));
        VersleuteldeGegevens gewist = new VersleuteldeGegevens(gegevens.ontvangerVersleuteld(),
                gegevens.personalisationVersleuteld(), null, gegevens.kekVersie());

        assertThrows(SleutelGewistException.class, () -> sleutelbeheer.ontsleutelOntvanger(ID, gewist));
        assertThrows(SleutelGewistException.class, () -> sleutelbeheer.ontsleutelPersonalisation(ID, gewist));
    }

    @Test
    void ontsleutel_sleutelZonderKekVersie_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of());
        VersleuteldeGegevens zonderVersie = new VersleuteldeGegevens(gegevens.ontvangerVersleuteld(),
                gegevens.personalisationVersleuteld(), gegevens.sleutelGewrapt(), null);

        assertThrows(OntsleutelenMisluktException.class, () -> sleutelbeheer.ontsleutelOntvanger(ID, zonderVersie));
    }

    @Test
    void ontsleutel_onbekendeKekVersie_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of());
        VersleuteldeGegevens versie7 = new VersleuteldeGegevens(gegevens.ontvangerVersleuteld(),
                gegevens.personalisationVersleuteld(), gegevens.sleutelGewrapt(), 7);

        OntsleutelenMisluktException fout = assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelOntvanger(ID, versie7));
        assertTrue(fout.getMessage().contains("7"));
    }

    @Test
    void ontsleutel_oudeKekVersieNietMeerGeconfigureerd_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of());
        Sleutelbeheer alleenVersie2 = sleutelbeheer(2, Map.of(2, KEK_2));

        assertThrows(OntsleutelenMisluktException.class, () -> alleenVersie2.ontsleutelOntvanger(ID, gegevens));
    }

    @Test
    void ontsleutel_sleutelGewraptMetAndereKek_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of());
        Sleutelbeheer andereKek = sleutelbeheer(1, Map.of(1, ANDERE_KEK));

        OntsleutelenMisluktException fout = assertThrows(OntsleutelenMisluktException.class,
                () -> andereKek.ontsleutelOntvanger(ID, gegevens));
        assertNotNull(fout.getCause());
    }

    @Test
    void ontsleutel_naKekRotatie_ontsleuteltOudeEnNieuweVersie() {
        VersleuteldeGegevens oud = sleutelbeheer.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of("naam", "Oud BV"));
        Sleutelbeheer naRotatie = sleutelbeheer(2, Map.of(1, KEK_1, 2, KEK_2));
        VersleuteldeGegevens nieuw = naRotatie.versleutel(ID, Ontvanger.email("ander@example.nl"), Map.of("naam", "Nieuw BV"));

        assertEquals(2, nieuw.kekVersie());
        assertEquals(Ontvanger.email("burger@example.nl"), naRotatie.ontsleutelOntvanger(ID, oud));
        assertEquals(Map.of("naam", "Oud BV"), naRotatie.ontsleutelPersonalisation(ID, oud));
        assertEquals(Ontvanger.email("ander@example.nl"), naRotatie.ontsleutelOntvanger(ID, nieuw));
    }

    @Test
    void ontsleutelPersonalisation_geenGeldigeJson_gooitZonderDeTekstTeNoemen() {
        // Kan alleen ontstaan door een fout buiten Sleutelbeheer. De melding van Jackson citeert de
        // ontsleutelde tekst; die mag niet in de exception of zijn oorzaak belanden.
        Sleutelbeheer kapotSerialiserend = new Sleutelbeheer(kekProvider(), new ObjectMapper() {
            @Override
            public byte[] writeValueAsBytes(Object value) {
                return "geheim".getBytes(StandardCharsets.UTF_8);
            }
        });
        VersleuteldeGegevens gegevens = kapotSerialiserend.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of());

        OntsleutelenMisluktException fout = assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelPersonalisation(ID, gegevens));
        assertNull(fout.getCause());
        assertFalse(fout.getMessage().contains("geheim"));
    }

    // Alle vier de kolommen van een andere rij overnemen levert geen geldige ontvanger op: de
    // notificatie-id zit in de associated data van elk veld en van de gewrapte sleutel.
    @Test
    void ontsleutel_gegevensVanEenAndereNotificatie_gooitOntsleutelenMisluktException() {
        UUID andereId = UUID.fromString("3f1a2b4c-0000-4000-8000-000000000002");
        VersleuteldeGegevens vanAndereRij = sleutelbeheer.versleutel(andereId, Ontvanger.email("ander@example.nl"), Map.of("naam", "Ander BV"));

        assertThrows(OntsleutelenMisluktException.class, () -> sleutelbeheer.ontsleutelOntvanger(ID, vanAndereRij));
        assertThrows(OntsleutelenMisluktException.class, () -> sleutelbeheer.ontsleutelPersonalisation(ID, vanAndereRij));
    }

    @Test
    void ontsleutelOntvanger_identificerendNummer_levertSoortEnNummerOp() {
        Ontvanger kvk = new Ontvanger(Ontvanger.Soort.KVK, "12345678");

        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel(ID, kvk, Map.of());

        assertEquals(kvk, sleutelbeheer.ontsleutelOntvanger(ID, gegevens));
    }

    @Test
    void versleutel_ciphertextBevatIvEnTag() throws Exception {
        Ontvanger ontvanger = Ontvanger.email("a@b.nl");
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel(ID, ontvanger, Map.of());

        // De ontvanger gaat als JSON de versleuteling in.
        assertEquals(12 + new ObjectMapper().writeValueAsBytes(ontvanger).length + 16, gegevens.ontvangerVersleuteld().length);
        assertEquals(12 + 32 + 16, gegevens.sleutelGewrapt().length);
    }

    @Test
    void wrap_zelfdeSleutelTweeKeer_levertVerschillendeWrapsDieBeideUitpakken() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey sleutel = generator.generateKey();

        byte[] eerste = sleutelbeheer.wrap(ID, sleutel, 1);
        byte[] tweede = sleutelbeheer.wrap(ID, sleutel, 1);

        assertFalse(Arrays.equals(eerste, tweede));
        assertArrayEquals(sleutel.getEncoded(), sleutelbeheer.pakUit(ID, new VersleuteldeGegevens(null, null, eerste, 1)).getEncoded());
        assertArrayEquals(sleutel.getEncoded(), sleutelbeheer.pakUit(ID, new VersleuteldeGegevens(null, null, tweede, 1)).getEncoded());
    }

    @Test
    void ontsleutel_gewijzigdeGewrapteSleutel_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of());
        byte[] gewrapt = gegevens.sleutelGewrapt().clone();
        gewrapt[gewrapt.length - 1] ^= 1;

        OntsleutelenMisluktException fout = assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelOntvanger(ID, metSleutel(gegevens, gewrapt, 1)));
        assertFalse(fout instanceof SleutelGewistException);
    }

    @Test
    void ontsleutel_teKorteGewrapteSleutel_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of());

        assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelOntvanger(ID, metSleutel(gegevens, new byte[27], 1)));
    }

    @Test
    void ontsleutel_gewraptOnderVersie1AlsVersie2Aangeboden_gooitOntsleutelenMisluktException() {
        Sleutelbeheer tweeVersies = sleutelbeheer(1, Map.of(1, KEK_1, 2, KEK_2));
        VersleuteldeGegevens gegevens = tweeVersies.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of());

        assertThrows(OntsleutelenMisluktException.class,
                () -> tweeVersies.ontsleutelOntvanger(ID, metSleutel(gegevens, gegevens.sleutelGewrapt(), 2)));
    }

    @Test
    void ontsleutel_zelfdeKekOnderAndereVersie_gooitOntsleutelenMisluktException() {
        Sleutelbeheer zelfdeKek = sleutelbeheer(1, Map.of(1, KEK_1, 2, KEK_1));
        VersleuteldeGegevens gegevens = zelfdeKek.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of());

        assertThrows(OntsleutelenMisluktException.class,
                () -> zelfdeKek.ontsleutelOntvanger(ID, metSleutel(gegevens, gegevens.sleutelGewrapt(), 2)));
    }

    @Test
    void ontsleutel_gewrapteSleutelAlsVeldAangeboden_gooitOntsleutelenMisluktException() {
        VersleuteldeGegevens gegevens = sleutelbeheer.versleutel(ID, Ontvanger.email("burger@example.nl"), Map.of());

        assertThrows(OntsleutelenMisluktException.class,
                () -> sleutelbeheer.ontsleutelOntvanger(ID, metOntvanger(gegevens, gegevens.sleutelGewrapt())));
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
