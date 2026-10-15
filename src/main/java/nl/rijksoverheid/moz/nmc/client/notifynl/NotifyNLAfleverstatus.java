package nl.rijksoverheid.moz.nmc.client.notifynl;

import java.time.OffsetDateTime;

/**
 * De afleverstatus van een verzending zoals NotifyNL hem bij een opvraag teruggeeft.
 *
 * @param status   de NotifyNL-status, zoals {@code delivered} of {@code sending}
 * @param tijdstip {@code completed_at}, met {@code sent_at} en {@code created_at} als terugval; null
 *                 als geen van drieën bruikbaar is
 */
public record NotifyNLAfleverstatus(String status, OffsetDateTime tijdstip) {
}
