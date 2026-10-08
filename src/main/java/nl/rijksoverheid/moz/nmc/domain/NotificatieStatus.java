package nl.rijksoverheid.moz.nmc.domain;

/**
 * De status in de levenscyclus van een notificatie. De uitkomst van een afzonderlijke verzending
 * staat op de poging ({@link PogingStatus}).
 */
public enum NotificatieStatus {
    AANGENOMEN,
    IN_VERZENDING,
    VERZONDEN,
    // Geen eindstatus: NotifyNL kan tot zeven dagen na delivered nog een faalreceipt sturen.
    BEZORGD,
    DEFINITIEF_BEZORGD,
    NIET_BEZORGBAAR,
    TECHNISCH_MISLUKT,
    // Eindstatus, maar een later binnengekomen receipt mag hem nog corrigeren.
    BEZORGSTATUS_ONBEKEND,
    VERLOPEN,
    GEANNULEERD
}
