package nl.rijksoverheid.moz.nmc.repository;

import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Een verlopen notificatie zoals de retentiejob hem meldt.
 * <p>
 * Top-level en niet genest, omdat een JPQL-constructorexpressie de klasse bij naam noemt en een
 * geneste klasse daar met een {@code $} in het pad zou staan. In dit package en niet bij de job, want
 * het is de vorm die een repositoryquery teruggeeft — andersom zou de repository omhoog moeten kijken.
 *
 * @param laatsteStatusUpdate de registratietijd van de laatste status, waar de bewaartermijn op vaart
 */
public record Kandidaat(UUID id, UUID externalReference, StatusWaarde status, OffsetDateTime laatsteStatusUpdate) {
}
