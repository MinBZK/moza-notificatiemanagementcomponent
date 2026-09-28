package nl.rijksoverheid.moz.nmc.domain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * De reden bij een terminale status, zoals de Dienstverlener hem ziet. Bewust grof: een onbekende
 * partij en een ontbrekende voorkeur vallen beide onder {@code GEEN_CONTACTGEGEVENS}.
 */
public enum Reden {
    ONBEREIKBAAR,
    GEEN_CONTACTGEGEVENS,
    VERLOPEN,
    GEANNULEERD,
    TECHNISCH;

    /** De kebab-case-weergave in de API en in het CloudEvent, bijvoorbeeld {@code niet-bezorgbaar}. */
    @JsonValue
    public String toApiValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
