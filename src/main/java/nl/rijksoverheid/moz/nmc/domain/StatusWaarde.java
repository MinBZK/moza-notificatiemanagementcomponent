package nl.rijksoverheid.moz.nmc.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum StatusWaarde {
    SENDING,
    DELIVERED,
    PERMANENT_FAILURE,
    TEMPORARY_FAILURE,
    TECHNICAL_FAILURE,
    CREATED,
    // NotifyNL's delivery-receipt-model kent meer statussen dan de bovenstaande (zie
    // notifynl_api.yaml); ONBEKEND vangt elke NotifyNL-status op die niet op één van hen afbeeldt
    // (zie NotificatieService#parseStatus), zodat zo'n status niet stilzwijgend als een bekende
    // (en mogelijk definitieve) status wordt geregistreerd.
    ONBEKEND;

    /** Returns the kebab-case representation for use in API responses (e.g. permanent-failure). */
    @JsonValue
    public String toApiValue() {
        return name().toLowerCase().replace('_', '-');
    }

    // Hardcoded (niet configureerbaar) — een keuze van de NMC zelf. Switch i.p.v. Set dwingt dat
    // elke toekomstige status hier expliciet wordt geclassificeerd (geen default-tak). CREATED/
    // SENDING zijn statussen waarna NotifyNL nog een vervolgcallback stuurt; de overige (bekende)
    // statussen zijn een eindstatus in NotifyNL's eigen delivery-receipt-model (zie
    // notifynl_api.yaml). ONBEKEND is voorzichtigheidshalve ook niet-definitief: van een status die
    // de NMC niet herkent, is niet vast te stellen of NotifyNL er nog een vervolgcallback over stuurt.
    public boolean isDefinitief() {
        return switch (this) {
            case DELIVERED, PERMANENT_FAILURE, TEMPORARY_FAILURE, TECHNICAL_FAILURE -> true;
            case CREATED, SENDING, ONBEKEND -> false;
        };
    }
}
