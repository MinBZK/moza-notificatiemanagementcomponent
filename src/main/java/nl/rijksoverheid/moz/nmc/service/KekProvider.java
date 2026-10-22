package nl.rijksoverheid.moz.nmc.service;

import javax.crypto.SecretKey;
import java.util.Optional;

/**
 * Levert de key encryption keys (KEK) waarmee {@link Sleutelbeheer} de sleutel per notificatie wrapt.
 * Nieuwe sleutels worden gewrapt met de huidige versie; oudere versies blijven nodig zolang er rijen
 * met die {@code kek_versie} bestaan.
 */
public interface KekProvider {

    int huidigeVersie();

    Optional<SecretKey> kek(int versie);
}
