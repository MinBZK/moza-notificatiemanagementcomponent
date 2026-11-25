package nl.rijksoverheid.moz.nmc.testhelper;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import nl.rijksoverheid.moz.nmc.service.sleutel.ConfigKekProvider;
import nl.rijksoverheid.moz.nmc.service.sleutel.Sleutelbeheer;

import java.util.HashMap;
import java.util.Map;

/** KEK's en het sleutelbeheer dat ermee werkt, voor tests zonder Quarkus. */
public final class SleutelFixtures {

    /** Twee willekeurige KEK's: base64 van 32 bytes. */
    public static final String KEK_1 = "I8JrSCkAfhntSWtJadWGZeT4SxH4Ld1x3N+aTWU4PGQ=";
    public static final String KEK_2 = "PAQwq3xsGw0w/Tbk4r61A2Yz+Cr049JCHzU8in/aWWg=";

    private SleutelFixtures() {
    }

    /** Sleutelbeheer met deze KEK per versie, en {@code huidigeVersie} voor nieuwe sleutels. */
    public static Sleutelbeheer sleutelbeheer(int huidigeVersie, Map<Integer, String> keks) {
        Map<String, String> config = new HashMap<>();
        config.put("nmc.kek.huidige-versie", String.valueOf(huidigeVersie));
        keks.forEach((versie, kek) -> config.put("nmc.kek.versie." + versie, kek));

        return new Sleutelbeheer(kekProvider(config), new ObjectMapper());
    }

    /** De provider zoals de applicatie hem bouwt, op deze properties. */
    public static ConfigKekProvider kekProvider(Map<String, String> config) {
        return new ConfigKekProvider(new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(config, "test", 100))
                .build());
    }
}
