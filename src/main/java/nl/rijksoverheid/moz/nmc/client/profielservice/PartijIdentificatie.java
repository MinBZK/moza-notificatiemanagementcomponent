package nl.rijksoverheid.moz.nmc.client.profielservice;

import jakarta.validation.constraints.NotNull;
import nl.rijksoverheid.moz.nmc.controller.IdentificatieType;

public record PartijIdentificatie(
        @NotNull IdentificatieType identificatieType,
        @NotNull String identificatieNummer,
        @NotNull String dienstverlener,
        String dienst) {
}
