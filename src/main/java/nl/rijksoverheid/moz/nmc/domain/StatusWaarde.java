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
     * Of NotifyNL iets over de verzending heeft teruggemeld: alles behalve {@code CREATED} en
     * {@code SENDING}.
     * <p>
     * Let op wat dit <em>niet</em> zegt: niet dat er geen status meer overheen komt. NotifyNL kan ná
     * een {@code DELIVERED} alsnog een faalstatus melden, en {@link #volgtOp} laat die dan ook toe.
     * Welke uitkomst uiteindelijk telt, is nog niet belegd; dat hoort bij de afhandeling van de
     * statussen zelf.
     */
    // Switch i.p.v. Set dwingt dat elke toekomstige status hier expliciet wordt geclassificeerd.
    public boolean isDefinitief() {
        return switch (this) {
            case DELIVERED, PERMANENT_FAILURE, TEMPORARY_FAILURE, TECHNICAL_FAILURE, ONBEKEND -> true;
            case CREATED, SENDING -> false;
        };
    }

    /**
     * Of deze status als nieuw record in de statusgeschiedenis hoort.
     * <p>
     * De NMC onderscheidt alleen de verzendfase ({@code CREATED}, {@code SENDING}) van wat NotifyNL
     * daarna terugmeldt. Binnen die terugmeldingen geldt geen rangorde: een definitieve status volgt
     * op elke andere definitieve status, want NotifyNL kan ná een bezorging alsnog een fout melden.
     * Alleen precies dezelfde status is geen nieuws — NotifyNL herhaalt een callback bij elke
     * niet-2xx, dus dat geval is de regel en niet de uitzondering.
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
