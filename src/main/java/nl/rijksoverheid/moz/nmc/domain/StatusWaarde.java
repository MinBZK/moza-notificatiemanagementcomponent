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
    // NotifyNL's delivery-receipt-model kent meer statussen dan de bovenstaande (zie
    // notifynl_api.yaml); ONBEKEND vangt elke NotifyNL-status op die niet op één van hen afbeeldt
    // (zie NotificatieService#parseStatus), zodat zo'n status niet stilzwijgend als een bekende
    // (en mogelijk definitieve) status wordt geregistreerd.
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

    // Rangorde van de statussen binnen een verzending, gebruikt door #volgtOp. NotifyNL biedt een
    // callback opnieuw aan bij elke niet-2xx, dus dezelfde delivery receipt kan meerdere keren
    // binnenkomen en twee receipts voor een verzending kunnen elkaar in omgekeerde volgorde
    // bereiken; de volgorde van binnenkomst zegt dus niets over de volgorde van de gebeurtenissen.
    // DELIVERED staat bewust boven de faalstatussen: is er eenmaal bezorgd, dan is dat de uitkomst
    // en mag geen enkele later binnenkomende faalstatus die terugdraaien. ONBEKEND staat tussen
    // SENDING en de eindstatussen in: er is wel iets teruggemeld, maar van een status die de NMC
    // niet kent is niet vast te stellen of hij een bekende uitkomst mag overschrijven.
    // Switch i.p.v. Map dwingt, net als in isDefinitief, dat elke toekomstige status hier expliciet
    // wordt ingedeeld (geen default-tak).
    private int rang() {
        return switch (this) {
            case CREATED -> 0;
            case SENDING -> 1;
            case ONBEKEND -> 2;
            case TEMPORARY_FAILURE, TECHNICAL_FAILURE, PERMANENT_FAILURE -> 3;
            case DELIVERED -> 4;
        };
    }

    /**
     * Of deze status een vooruitgang is ten opzichte van de al vastgelegde status, en dus als nieuw
     * record in de statusgeschiedenis hoort.
     * <p>
     * Een gelijke rang telt niet als vooruitgang: dat is een herhaling van dezelfde receipt, of een
     * tweede en afwijkende eindstatus voor dezelfde verzending. In beide gevallen blijft de eerst
     * vastgelegde uitkomst staan.
     */
    public boolean volgtOp(StatusWaarde vastgelegdeStatus) {
        return rang() > vastgelegdeStatus.rang();
    }
}
