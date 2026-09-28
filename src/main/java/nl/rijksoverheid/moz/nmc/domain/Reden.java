package nl.rijksoverheid.moz.nmc.domain;

/**
 * De reden bij een terminale status, zoals de Dienstverlener hem ziet. Bewust grof: een onbekende
 * partij en een ontbrekende voorkeur vallen beide onder {@code GEEN_CONTACTGEGEVENS}.
 */
public enum Reden {
    ONBEREIKBAAR,
    GEEN_CONTACTGEGEVENS,
    VERLOPEN,
    GEANNULEERD,
    TECHNISCH
}
