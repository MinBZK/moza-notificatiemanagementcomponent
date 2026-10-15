package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Het verzendbudget van één Notify-service in één tijdvak van een minuut. Een vast aandeel
 * ({@code navraagTokens}) is voor de navraag: verzenden laat het staan en de navraag neemt alleen
 * daaruit. Wijzig alleen een rij die met een rijlock is gelezen, zodat twee gelijktijdige claims elkaars
 * tokens niet dubbel nemen.
 */
@Entity
@Table(name = "verzendbudget")
@IdClass(VerzendbudgetTijdvak.Sleutel.class)
public class VerzendbudgetTijdvak {

    @Id
    @Column(name = "notify_service", length = 64)
    private String notifyService;

    @Id
    private OffsetDateTime tijdvak;

    @Column(nullable = false)
    private int tokens;

    @Column(name = "navraag_tokens", nullable = false)
    private int navraagTokens;

    protected VerzendbudgetTijdvak() {
        // Voor JPA
    }

    /** Het aantal tokens dat er voor dit doel nog te nemen is. */
    public int beschikbaar(BudgetDoel doel) {
        return doel == BudgetDoel.NAVRAAG ? navraagTokens : tokens - navraagTokens;
    }

    public void neem(BudgetDoel doel, int aantal) {
        if (aantal < 0 || aantal > beschikbaar(doel)) {
            throw new IllegalArgumentException("Kan geen " + aantal + " tokens nemen voor " + doel + ", er zijn er "
                    + beschikbaar(doel));
        }

        tokens -= aantal;

        if (doel == BudgetDoel.NAVRAAG) {
            navraagTokens -= aantal;
        }
    }

    /** Geeft tokens terug, tot ten hoogste {@code maximum} en voor de navraag {@code navraagMaximum}. */
    public void geefTerug(BudgetDoel doel, int aantal, int maximum, int navraagMaximum) {
        tokens = Math.min(tokens + aantal, maximum);

        if (doel == BudgetDoel.NAVRAAG) {
            navraagTokens = Math.min(navraagTokens + aantal, navraagMaximum);
        }
    }

    public record Sleutel(String notifyService, OffsetDateTime tijdvak) {
    }
}
