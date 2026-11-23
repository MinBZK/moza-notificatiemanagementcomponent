package nl.rijksoverheid.moz.nmc.service;

import java.util.Optional;
import javax.crypto.SecretKey;

/**
 * Levert de key encryption keys (KEK) waarmee {@link Sleutelbeheer} de sleutel per notificatie wrapt.
 * Nieuwe sleutels worden gewrapt met de huidige versie; oudere versies blijven nodig zolang er rijen
 * met die {@code kek_versie} bestaan.
 */
public interface KekProvider {

    int huidigeVersie();

    Optional<SecretKey> kek(int versie);
}
