package nl.rijksoverheid.moz.nmc.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * De cursor tot waar de Dienstverlener de feed heeft verwerkt, door hem zelf teruggeschreven. Eén
 * per dienstverlener; hij gaat alleen vooruit.
 */
public record Bevestiging(UUID dvId, Cursor cursor, OffsetDateTime bevestigdOp) {
}
