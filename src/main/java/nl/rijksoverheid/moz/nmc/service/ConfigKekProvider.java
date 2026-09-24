package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Leest de KEK's uit de configuratie: {@code nmc.kek.huidige-versie} en per versie
 * {@code nmc.kek.versie.<n>} met een base64-gecodeerde sleutel van 256 bits. Als env-var:
 * {@code NMC_KEK_HUIDIGE_VERSIE} en {@code NMC_KEK_VERSIE_<n>}.
 * <p>
 * Eager bij het opstarten, zodat een ontbrekende of ongeldige KEK de applicatie niet laat starten.
 */
@Startup
@ApplicationScoped
public class ConfigKekProvider implements KekProvider {

    static final String HUIDIGE_VERSIE = "nmc.kek.huidige-versie";
    static final String VERSIE_PREFIX = "nmc.kek.versie.";

    private static final int KEK_LENGTE_BYTES = 32;

    private final int huidigeVersie;
    private final Map<Integer, SecretKey> keks = new HashMap<>();

    @Inject
    public ConfigKekProvider(Config config) {
        this(naam -> config.getOptionalValue(naam, String.class));
    }

    ConfigKekProvider(Function<String, Optional<String>> configWaarde) {
        String versie = configWaarde.apply(HUIDIGE_VERSIE)
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new IllegalStateException(HUIDIGE_VERSIE + " is niet geconfigureerd"));
        this.huidigeVersie = leesVersie(versie.strip());

        // Een oudere versie mag ontbreken, bijvoorbeeld nadat alle sleutels zijn geherwrapt.
        for (int v = 1; v <= huidigeVersie; v++) {
            int kekVersie = v;
            configWaarde.apply(VERSIE_PREFIX + kekVersie)
                    .filter(s -> !s.isBlank())
                    .ifPresent(waarde -> keks.put(kekVersie, leesKek(kekVersie, waarde.strip())));
        }

        if (!keks.containsKey(huidigeVersie)) {
            throw new IllegalStateException(VERSIE_PREFIX + huidigeVersie
                    + " is niet geconfigureerd, terwijl " + HUIDIGE_VERSIE + " naar die versie wijst");
        }
    }

    @Override
    public int huidigeVersie() {
        return huidigeVersie;
    }

    @Override
    public Optional<SecretKey> kek(int versie) {
        return Optional.ofNullable(keks.get(versie));
    }

    private static int leesVersie(String waarde) {
        int versie;

        try {
            versie = Integer.parseInt(waarde);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(HUIDIGE_VERSIE + " is geen geheel getal: " + waarde);
        }

        if (versie < 1) {
            throw new IllegalStateException(HUIDIGE_VERSIE + " moet 1 of hoger zijn, is " + versie);
        }

        return versie;
    }

    // De foutmeldingen noemen nooit de waarde zelf, want die is geheim.
    private static SecretKey leesKek(int versie, String waarde) {
        byte[] sleutel;

        try {
            sleutel = Base64.getDecoder().decode(waarde);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(VERSIE_PREFIX + versie + " is geen geldige base64");
        }

        if (sleutel.length != KEK_LENGTE_BYTES) {
            throw new IllegalStateException(VERSIE_PREFIX + versie + " moet " + KEK_LENGTE_BYTES
                    + " bytes (256 bits) zijn, is " + sleutel.length + " bytes");
        }

        return new SecretKeySpec(sleutel, "AES");
    }
}
