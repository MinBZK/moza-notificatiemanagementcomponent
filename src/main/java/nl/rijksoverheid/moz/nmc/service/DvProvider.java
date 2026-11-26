package nl.rijksoverheid.moz.nmc.service;

import java.util.UUID;

/**
 * Levert de dienstverlener namens wie een aanroep wordt gedaan.
 */
public interface DvProvider {

    UUID huidigeDvId();
}
