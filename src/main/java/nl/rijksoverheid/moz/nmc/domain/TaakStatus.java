package nl.rijksoverheid.moz.nmc.domain;

/** Een taak is open tot hij is afgerond (dan verdwijnt de rij) of uitgeput (dan wacht hij op beheer). */
public enum TaakStatus {
    OPEN,
    MISLUKT
}
