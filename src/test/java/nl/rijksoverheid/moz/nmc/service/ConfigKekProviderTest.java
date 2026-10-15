package nl.rijksoverheid.moz.nmc.service;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigKekProviderTest {

    private static final String KEK_1 = SleutelbeheerTest.KEK_1;
    private static final String KEK_2 = SleutelbeheerTest.KEK_2;

    @Test
    void constructor_huidigeVersieMetGeldigeKek_levertKekOp() {
        ConfigKekProvider provider = provider(Map.of("nmc.kek.huidige-versie", "1", "nmc.kek.versie.1", KEK_1));

        assertEquals(1, provider.huidigeVersie());
        assertArrayEquals(Base64.getDecoder().decode(KEK_1), provider.kek(1).orElseThrow().getEncoded());
        assertTrue(provider.kek(2).isEmpty());
    }

    @Test
    void constructor_meerdereVersies_levertAlleGeconfigureerdeVersiesOp() {
        ConfigKekProvider provider = provider(Map.of(
                "nmc.kek.huidige-versie", "2", "nmc.kek.versie.1", KEK_1, "nmc.kek.versie.2", KEK_2));

        assertEquals(2, provider.huidigeVersie());
        assertTrue(provider.kek(1).isPresent());
        assertTrue(provider.kek(2).isPresent());
    }

    @Test
    void constructor_oudereVersieOntbreekt_startGewoon() {
        ConfigKekProvider provider = provider(Map.of("nmc.kek.huidige-versie", "3", "nmc.kek.versie.3", KEK_1));

        assertTrue(provider.kek(3).isPresent());
        assertFalse(provider.kek(1).isPresent());
    }

    // Na het terugzetten van de huidige versie moeten rijen onder de hogere versie leesbaar blijven.
    @Test
    void constructor_versieBovenHuidigeVersie_wordtOokGelezen() {
        ConfigKekProvider provider = provider(Map.of(
                "nmc.kek.huidige-versie", "1", "nmc.kek.versie.1", KEK_1, "nmc.kek.versie.2", KEK_2));

        assertEquals(1, provider.huidigeVersie());
        assertArrayEquals(Base64.getDecoder().decode(KEK_2), provider.kek(2).orElseThrow().getEncoded());
    }

    @Test
    void constructor_ongeldigeKekBovenHuidigeVersie_gooitIllegalStateException() {
        assertThrows(IllegalStateException.class, () -> provider(Map.of(
                "nmc.kek.huidige-versie", "1", "nmc.kek.versie.1", KEK_1, "nmc.kek.versie.2", "geen-base64!")));
    }

    // Een tikfout in de versie mag het opstarten niet laten hangen; hij wordt direct geweigerd.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"101", "1000000000", "2147483647"})
    void constructor_huidigeVersieBovenMaximum_gooitIllegalStateException(String versie) {
        IllegalStateException fout = assertThrows(IllegalStateException.class,
                () -> provider(Map.of("nmc.kek.huidige-versie", versie, "nmc.kek.versie.1", KEK_1)));

        assertTrue(fout.getMessage().contains("tussen 1 en " + ConfigKekProvider.MAX_VERSIE));
    }

    @Test
    void constructor_huidigeVersieOntbreekt_gooitIllegalStateException() {
        IllegalStateException fout = assertThrows(IllegalStateException.class,
                () -> provider(Map.of("nmc.kek.versie.1", KEK_1)));

        assertTrue(fout.getMessage().contains("nmc.kek.huidige-versie"));
    }

    @Test
    void constructor_huidigeVersieLeeg_gooitIllegalStateException() {
        assertThrows(IllegalStateException.class,
                () -> provider(Map.of("nmc.kek.huidige-versie", " ", "nmc.kek.versie.1", KEK_1)));
    }

    @Test
    void constructor_huidigeVersieGeenGetal_gooitIllegalStateException() {
        IllegalStateException fout = assertThrows(IllegalStateException.class,
                () -> provider(Map.of("nmc.kek.huidige-versie", "een", "nmc.kek.versie.1", KEK_1)));

        assertTrue(fout.getMessage().contains("geen geheel getal"));
    }

    @Test
    void constructor_huidigeVersieNul_gooitIllegalStateException() {
        assertThrows(IllegalStateException.class, () -> provider(Map.of("nmc.kek.huidige-versie", "0")));
    }

    @Test
    void constructor_kekVanHuidigeVersieOntbreekt_gooitIllegalStateException() {
        IllegalStateException fout = assertThrows(IllegalStateException.class,
                () -> provider(Map.of("nmc.kek.huidige-versie", "2", "nmc.kek.versie.1", KEK_1)));

        assertTrue(fout.getMessage().contains("nmc.kek.versie.2"));
    }

    @Test
    void constructor_kekLeeg_gooitIllegalStateException() {
        assertThrows(IllegalStateException.class,
                () -> provider(Map.of("nmc.kek.huidige-versie", "1", "nmc.kek.versie.1", "")));
    }

    @Test
    void constructor_kekTeKort_gooitIllegalStateExceptionZonderDeWaardeTeNoemen() {
        String kek16Bytes = "HVhacgB357UNLEhaENCUbQ==";

        IllegalStateException fout = assertThrows(IllegalStateException.class,
                () -> provider(Map.of("nmc.kek.huidige-versie", "1", "nmc.kek.versie.1", kek16Bytes)));

        assertTrue(fout.getMessage().contains("32 bytes"));
        assertTrue(fout.getMessage().contains("16 bytes"));
        assertFalse(fout.getMessage().contains(kek16Bytes));
    }

    @Test
    void constructor_kekTeLang_gooitIllegalStateException() {
        String kek33Bytes = Base64.getEncoder().encodeToString(new byte[33]);

        assertThrows(IllegalStateException.class,
                () -> provider(Map.of("nmc.kek.huidige-versie", "1", "nmc.kek.versie.1", kek33Bytes)));
    }

    @Test
    void constructor_kekVanOudereVersieOngeldig_gooitIllegalStateException() {
        assertThrows(IllegalStateException.class, () -> provider(Map.of(
                "nmc.kek.huidige-versie", "2", "nmc.kek.versie.1", "HVhacgB357UNLEhaENCUbQ==", "nmc.kek.versie.2", KEK_2)));
    }

    @Test
    void constructor_kekGeenBase64_gooitIllegalStateExceptionZonderDeWaardeTeNoemen() {
        String geenBase64 = "dit-is-geen-base64-maar-wel-geheim!";

        IllegalStateException fout = assertThrows(IllegalStateException.class,
                () -> provider(Map.of("nmc.kek.huidige-versie", "1", "nmc.kek.versie.1", geenBase64)));

        assertTrue(fout.getMessage().contains("base64"));
        assertFalse(fout.getMessage().contains(geenBase64));
    }

    @Test
    void constructor_viaEnvVars_leestNmcKekHuidigeVersieEnNmcKekVersieN() {
        // Zo komen de waarden op ZAD binnen: als env-var, via de name-mangling van SmallRye Config.
        Map<String, String> env = new HashMap<>();
        env.put("NMC_KEK_HUIDIGE_VERSIE", "2");
        env.put("NMC_KEK_VERSIE_1", KEK_1);
        env.put("NMC_KEK_VERSIE_2", KEK_2);
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withSources(new EnvConfigSource(env, 300))
                .build();

        ConfigKekProvider provider = new ConfigKekProvider(config);

        assertEquals(2, provider.huidigeVersie());
        assertTrue(provider.kek(1).isPresent());
        assertTrue(provider.kek(2).isPresent());
    }

    private static ConfigKekProvider provider(Map<String, String> config) {
        return new ConfigKekProvider(naam -> Optional.ofNullable(config.get(naam)));
    }
}
