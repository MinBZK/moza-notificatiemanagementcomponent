package nl.rijksoverheid.moz.nmc.domain;

/**
 * De uitkomst van één verzending bij NotifyNL, afgeleid van de afleverstatussen in de receipts.
 * Een poging is lopend zolang haar status {@code VERZONDEN} of {@code ONBEKEND} is.
 */
public enum PogingStatus {
    GEPLAND,
    VERZONDEN,
    BEZORGD,
    TIJDELIJK_MISLUKT,
    PERMANENT_MISLUKT,
    TECHNISCH_MISLUKT,
    ONBEKEND
}
