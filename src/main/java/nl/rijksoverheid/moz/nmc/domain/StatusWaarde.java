package nl.rijksoverheid.moz.nmc.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum StatusWaarde {
    SENDING,
    DELIVERED,
    PERMANENT_FAILURE,
    TEMPORARY_FAILURE,
    TECHNICAL_FAILURE,
    CREATED;

    /** Returns the kebab-case representation for use in API responses (e.g. permanent-failure). */
    @JsonValue
    public String toApiValue() {
        return name().toLowerCase().replace('_', '-');
    }

    // Hardcoded (niet configureerbaar) — een keuze van de NMC zelf. Switch i.p.v. Set dwingt dat
    // elke toekomstige status hier expliciet wordt geclassificeerd (geen default-tak). CREATED/
    // SENDING zijn de enige statussen waarna NotifyNL nog een vervolgcallback stuurt; de rest is
    // een eindstatus in NotifyNL's eigen delivery-receipt-model (zie notifynl_api.yaml).
    public boolean isDefinitief() {
        return switch (this) {
            case DELIVERED, PERMANENT_FAILURE, TEMPORARY_FAILURE, TECHNICAL_FAILURE -> true;
            case CREATED, SENDING -> false;
        };
    }
}
