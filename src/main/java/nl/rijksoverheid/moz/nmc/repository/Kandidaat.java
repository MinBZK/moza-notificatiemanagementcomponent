package nl.rijksoverheid.moz.nmc.repository;

import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Een verlopen notificatie zoals de retentiejob hem meldt.
 * <p>
 * Top-level en niet genest, omdat een JPQL-constructorexpressie de klasse bij naam noemt en een
 * geneste klasse daar een {@code $} in het pad krijgt. In dit package omdat een repositoryquery hem
 * teruggeeft.
 *
 * @param laatsteStatusUpdate de registratietijd van de laatste overgang, waar de bewaartermijn op vaart
 */
public record Kandidaat(UUID id, NotificatieStatus status, OffsetDateTime laatsteStatusUpdate) {
}
