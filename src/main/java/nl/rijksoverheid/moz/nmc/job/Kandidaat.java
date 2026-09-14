package nl.rijksoverheid.moz.nmc.job;

import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Een verlopen notificatie zoals de retentiejob hem meldt.
 * <p>
 * Top-level en niet genest in {@link NotificatieRetentieScheduler}, omdat een
 * JPQL-constructorexpressie de klasse bij naam noemt en een geneste klasse daar met een
 * {@code $} in het pad zou staan.
 *
 * @param laatsteStatusUpdate de registratietijd van de laatste status, waar de bewaartermijn op vaart
 */
record Kandidaat(UUID id, UUID externalReference, StatusWaarde status, OffsetDateTime laatsteStatusUpdate) {
}
