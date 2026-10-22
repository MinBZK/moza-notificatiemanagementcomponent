package nl.rijksoverheid.moz.nmc.domain;

/**
 * Waarvoor een claim tokens uit het verzendbudget neemt. Een vast aandeel van elk tijdvak is
 * gereserveerd voor de navraag, zodat een piek in het verzenden de navraag niet verdringt.
 */
public enum BudgetDoel {
    /** Verzenden bij NotifyNL; mag de reservering voor de navraag niet aanspreken. */
    VERZENDEN,
    /** Statussen opvragen bij NotifyNL; mag alle resterende tokens gebruiken. */
    NAVRAAG
}
