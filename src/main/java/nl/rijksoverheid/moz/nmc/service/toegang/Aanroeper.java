package nl.rijksoverheid.moz.nmc.service.toegang;

import jakarta.enterprise.context.RequestScoped;

import java.util.Optional;
import java.util.UUID;

/**
 * De dienstverlener van de lopende aanroep, zoals vastgesteld uit het FSC-toegangstoken. Leeg tot de
 * controle van het token hem zet.
 */
@RequestScoped
public class Aanroeper {

    private UUID dvId;

    public void zetDvId(UUID dvId) {
        this.dvId = dvId;
    }

    public Optional<UUID> dvId() {
        return Optional.ofNullable(dvId);
    }
}
