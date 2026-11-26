package nl.rijksoverheid.moz.nmc.service.toegang;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.config.FscConfig;
import nl.rijksoverheid.moz.nmc.service.DvProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.UUID;

/**
 * Levert de dienstverlener uit het FSC-toegangstoken van de lopende aanroep. Staat de controle van
 * het token uit, dan is het de vaste dienstverlener uit {@code nmc.dienstverlener.id}; de rij ervoor
 * komt uit de migratie. Eager bij het opstarten, zodat een ongeldige waarde de applicatie niet laat
 * starten.
 */
@Startup
@ApplicationScoped
public class AanroeperDvProvider implements DvProvider {

    private final boolean uitToken;
    private final Aanroeper aanroeper;
    private final UUID geconfigureerdeDvId;

    public AanroeperDvProvider(FscConfig fsc, Aanroeper aanroeper,
                               @ConfigProperty(name = "nmc.dienstverlener.id") String dvId) {
        this.uitToken = fsc.ingeschakeld();
        this.aanroeper = aanroeper;

        try {
            this.geconfigureerdeDvId = UUID.fromString(dvId.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("nmc.dienstverlener.id is geen UUID: " + dvId);
        }
    }

    /**
     * @throws IllegalStateException als de controle van het token aan staat en er voor deze aanroep geen
     *                               dienstverlener is vastgesteld
     */
    @Override
    public UUID huidigeDvId() {
        if (!uitToken) {
            return geconfigureerdeDvId;
        }

        return aanroeper.dvId()
                .orElseThrow(() -> new IllegalStateException("Voor deze aanroep is geen dienstverlener vastgesteld"));
    }
}
