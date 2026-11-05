package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.UUID;

/**
 * Eén vaste dienstverlener uit {@code nmc.dienstverlener.id}; de rij ervoor komt uit de migratie.
 * Eager bij het opstarten, zodat een ongeldige waarde de applicatie niet laat starten.
 */
@Startup
@ApplicationScoped
public class ConfigDvProvider implements DvProvider {

    private final UUID dvId;

    public ConfigDvProvider(@ConfigProperty(name = "nmc.dienstverlener.id") String dvId) {
        try {
            this.dvId = UUID.fromString(dvId.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("nmc.dienstverlener.id is geen UUID: " + dvId);
        }
    }

    @Override
    public UUID huidigeDvId() {
        return dvId;
    }
}
