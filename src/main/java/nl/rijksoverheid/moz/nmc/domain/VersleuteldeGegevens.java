package nl.rijksoverheid.moz.nmc.domain;

/**
 * Ontvanger en personalisation van één notificatie, versleuteld met een eigen sleutel die gewrapt is
 * door de KEK van {@code kekVersie}. Een {@code sleutelGewrapt} van null betekent dat de sleutel gewist
 * is of nooit is vastgelegd.
 */
public record VersleuteldeGegevens(byte[] ontvangerVersleuteld,
                                   byte[] personalisationVersleuteld,
                                   byte[] sleutelGewrapt,
                                   Integer kekVersie) {
}
