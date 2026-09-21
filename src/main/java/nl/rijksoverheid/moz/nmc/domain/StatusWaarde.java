package nl.rijksoverheid.moz.nmc.domain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum StatusWaarde {
    SENDING,
    DELIVERED,
    PERMANENT_FAILURE,
    TEMPORARY_FAILURE,
    TECHNICAL_FAILURE,
    CREATED,
    // Vangt elke NotifyNL-status op die niet op één van de bovenstaande afbeeldt (zie
    // NotificatieService#parseStatus), zodat die niet stilzwijgend als een bekende status landt.
    ONBEKEND;

    /**
     * De kebab-case-weergave voor API-antwoorden, bijvoorbeeld {@code permanent-failure}.
     * <p>
     * Locale.ROOT en niet de standaardlocale van de JVM: in een Turkse locale maakt
     * {@code toLowerCase()} van de I een dotless i, en die waarde gaat via het CloudEvent
     * rechtstreeks naar de Dienstverlener.
     */
    @JsonValue
    public String toApiValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Alles behalve {@code CREATED} en {@code SENDING}. Een definitieve status kan nog door een andere
     * definitieve status overschreven worden; welke uitkomst uiteindelijk telt is nog niet belegd.
     */
    // Switch i.p.v. Set dwingt dat elke toekomstige status hier expliciet wordt geclassificeerd.
    public boolean isDefinitief() {
        return switch (this) {
            case DELIVERED, PERMANENT_FAILURE, TEMPORARY_FAILURE, TECHNICAL_FAILURE, ONBEKEND -> true;
            case CREATED, SENDING -> false;
        };
    }

    /**
     * Of deze status als nieuw record in de statusgeschiedenis hoort. Geweigerd worden een herhaling
     * van de vastgelegde status en een teruggang naar de verzendfase; elke definitieve status volgt
     * op elke andere.
     */
    public boolean volgtOp(StatusWaarde vastgelegdeStatus) {
        if (this == vastgelegdeStatus) {
            return false;
        }

        if (isDefinitief()) {
            return true;
        }

        return this == SENDING && vastgelegdeStatus == CREATED;
    }
}
