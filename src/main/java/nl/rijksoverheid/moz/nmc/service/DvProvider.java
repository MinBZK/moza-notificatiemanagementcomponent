package nl.rijksoverheid.moz.nmc.service;

import java.util.UUID;

/**
 * Levert de dienstverlener namens wie een aanroep wordt gedaan. Tot de tokenvalidatie per
 * dienstverlener er is, is dat één geconfigureerde waarde; daarna komt hij uit het toegangstoken.
 */
public interface DvProvider {

    UUID huidigeDvId();
}
