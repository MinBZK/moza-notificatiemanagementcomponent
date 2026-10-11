package nl.rijksoverheid.moz.nmc.domain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

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
    GEANNULEERD;

    /**
     * Een eindstatus: geen uitgaande overgang meer, behalve dat {@code BEZORGSTATUS_ONBEKEND} door een
     * laat event nog gecorrigeerd mag worden.
     */
    public boolean isEindstatus() {
        return switch (this) {
            case DEFINITIEF_BEZORGD, NIET_BEZORGBAAR, TECHNISCH_MISLUKT, BEZORGSTATUS_ONBEKEND, VERLOPEN, GEANNULEERD -> true;
            case AANGENOMEN, IN_VERZENDING, VERZONDEN, BEZORGD -> false;
        };
    }

    /** De kebab-case-weergave in de API en in het CloudEvent, bijvoorbeeld {@code niet-bezorgbaar}. */
    @JsonValue
    public String toApiValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
