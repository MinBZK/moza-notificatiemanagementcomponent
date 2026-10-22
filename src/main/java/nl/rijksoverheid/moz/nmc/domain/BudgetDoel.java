package nl.rijksoverheid.moz.nmc.domain;

/**
 * Waarvoor een claim tokens uit het verzendbudget neemt. Elk doel heeft per tijdvak een reservering
 * die het andere laat staan, zodat een piek in het ene het andere niet verdringt; de rest delen ze.
 */
public enum BudgetDoel {
    /** Verzenden bij NotifyNL. */
    VERZENDEN,
    /** Statussen opvragen bij NotifyNL. */
    NAVRAAG
}
