package nl.rijksoverheid.moz.nmc.repository;

import java.util.UUID;

/** De gewrapte sleutel van een notificatie met de KEK-versie waaronder hij gewrapt is. */
public record GewrapteSleutel(UUID notificatieId, byte[] sleutel, int kekVersie) {
}
