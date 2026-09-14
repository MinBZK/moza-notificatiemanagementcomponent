package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import java.util.UUID;

/**
 * De payload van het CloudEvent naar de Dienstverlener. status is een String en geen StatusWaarde:
 * dit is een extern contract, dus de waarde staat hier los van de interne enum.
 */
public record NotificatieData(UUID notificatieId, String status) {
}
