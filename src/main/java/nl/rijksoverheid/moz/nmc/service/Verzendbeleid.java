package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Het verzendbeleid per berichttype: hoe lang een notificatie na de aanname nog verstuurd mag worden
 * ({@code geldig_tot}), hoe lang de herverzending wacht en hoeveel herverzendingen er zijn. Per
 * berichttype te overschrijven met {@code nmc.beleid.<berichttype>.geldigheid},
 * {@code .herverzend-wachttijd} en {@code .max-herverzendingen}, met de enum-naam in kleine letters;
 * anders geldt de standaard. Eager bij het opstarten, zodat een negatief aantal de applicatie niet laat
 * starten.
 */
@Startup
@ApplicationScoped
public class Verzendbeleid {

    private final Duration geldigheid;
    private final Duration herverzendWachttijd;
    private final int maxHerverzendingen;
    private final Config config;

    public Verzendbeleid(@ConfigProperty(name = "nmc.beleid.geldigheid") Duration geldigheid,
                         @ConfigProperty(name = "nmc.beleid.herverzend-wachttijd") Duration herverzendWachttijd,
                         @ConfigProperty(name = "nmc.beleid.max-herverzendingen") int maxHerverzendingen,
                         Config config) {
        this.geldigheid = geldigheid;
        this.herverzendWachttijd = herverzendWachttijd;
        this.maxHerverzendingen = maxHerverzendingen;
        this.config = config;

        if (maxHerverzendingen < 0) {
            throw new IllegalStateException("nmc.beleid.max-herverzendingen mag niet negatief zijn");
        }

        for (BerichtType berichtType : BerichtType.values()) {
            if (maxHerverzendingen(berichtType.name()) < 0) {
                throw new IllegalStateException("nmc.beleid." + berichtType.name().toLowerCase(Locale.ROOT)
                        + ".max-herverzendingen mag niet negatief zijn");
            }
        }
    }

    public Duration geldigheid(BerichtType berichtType) {
        return waarde(berichtType == null ? null : berichtType.name(), "geldigheid", geldigheid);
    }

    /** @param berichtType de enum-naam zoals op de notificatie; leeg voor rijen van vóór het beleid */
    public Duration herverzendWachttijd(String berichtType) {
        return waarde(berichtType, "herverzend-wachttijd", herverzendWachttijd);
    }

    /** @param berichtType de enum-naam zoals op de notificatie; leeg voor rijen van vóór het beleid */
    public int maxHerverzendingen(String berichtType) {
        return waarde(berichtType, "max-herverzendingen", Integer.class).orElse(maxHerverzendingen);
    }

    /**
     * Of na deze poging een herverzending volgt: alleen na een tijdelijke of technische fout, en zolang het
     * beleid van het berichttype er nog een toestaat. Poging 1 is de eerste verzending.
     */
    public boolean magHerverzenden(Poging poging, String berichtType) {
        return (poging.getStatus() == PogingStatus.TIJDELIJK_MISLUKT || poging.getStatus() == PogingStatus.TECHNISCH_MISLUKT)
                && poging.getNummer() <= maxHerverzendingen(berichtType);
    }

    private Duration waarde(String berichtType, String sleutel, Duration standaard) {
        return waarde(berichtType, sleutel, Duration.class).orElse(standaard);
    }

    private <T> Optional<T> waarde(String berichtType, String sleutel, Class<T> type) {
        if (berichtType == null) {
            return Optional.empty();
        }

        return config.getOptionalValue("nmc.beleid." + berichtType.toLowerCase(Locale.ROOT) + "." + sleutel, type);
    }
}
