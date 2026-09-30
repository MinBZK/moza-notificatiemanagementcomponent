package nl.rijksoverheid.moz.nmc.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.Locale;

/**
 * Het verzendbeleid per berichttype: hoe lang een notificatie na de aanname nog verstuurd mag worden
 * ({@code geldig_tot}) en hoe lang de herverzending wacht. Per berichttype te overschrijven met
 * {@code nmc.beleid.<berichttype>.geldigheid} en {@code nmc.beleid.<berichttype>.herverzend-wachttijd},
 * met de enum-naam in kleine letters; anders geldt de standaard.
 */
@ApplicationScoped
public class Verzendbeleid {

    private final Duration geldigheid;
    private final Duration herverzendWachttijd;
    private final Config config;

    public Verzendbeleid(@ConfigProperty(name = "nmc.beleid.geldigheid") Duration geldigheid,
                         @ConfigProperty(name = "nmc.beleid.herverzend-wachttijd") Duration herverzendWachttijd,
                         Config config) {
        this.geldigheid = geldigheid;
        this.herverzendWachttijd = herverzendWachttijd;
        this.config = config;
    }

    public Duration geldigheid(BerichtType berichtType) {
        return waarde(berichtType == null ? null : berichtType.name(), "geldigheid", geldigheid);
    }

    /** @param berichtType de enum-naam zoals op de notificatie; leeg voor rijen van vóór het beleid */
    public Duration herverzendWachttijd(String berichtType) {
        return waarde(berichtType, "herverzend-wachttijd", herverzendWachttijd);
    }

    private Duration waarde(String berichtType, String sleutel, Duration standaard) {
        if (berichtType == null) {
            return standaard;
        }

        return config.getOptionalValue("nmc.beleid." + berichtType.toLowerCase(Locale.ROOT) + "." + sleutel, Duration.class)
                .orElse(standaard);
    }
}
