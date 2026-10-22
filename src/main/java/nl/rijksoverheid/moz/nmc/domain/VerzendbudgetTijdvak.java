package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Het verzendbudget van één Notify-service in één tijdvak van een minuut. Verzenden en navraag hebben
 * elk een reservering ({@code verzendTokens}, {@code navraagTokens}) die de ander laat staan; wat daar
 * boven over is, is voor wie het eerst claimt. Wijzig alleen een rij die met een rijlock is gelezen,
 * zodat twee gelijktijdige claims elkaars tokens niet dubbel nemen.
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

    @Column(name = "verzend_tokens", nullable = false)
    private int verzendTokens;

    @Column(name = "navraag_tokens", nullable = false)
    private int navraagTokens;

    protected VerzendbudgetTijdvak() {
        // Voor JPA
    }

    /** Het aantal tokens dat er voor dit doel nog te nemen is: alles behalve de reservering van de ander. */
    public int beschikbaar(BudgetDoel doel) {
        return doel == BudgetDoel.NAVRAAG ? tokens - verzendTokens : tokens - navraagTokens;
    }

    /**
     * Neemt eerst uit de eigen reservering en daarna uit de gedeelde ruimte.
     *
     * @return hoeveel ervan uit de eigen reservering kwam
     */
    public int neem(BudgetDoel doel, int aantal) {
        if (aantal < 0 || aantal > beschikbaar(doel)) {
            throw new IllegalArgumentException("Kan geen " + aantal + " tokens nemen voor " + doel + ", er zijn er "
                    + beschikbaar(doel));
        }

        int uitReservering = Math.min(aantal, reservering(doel));
        tokens -= aantal;
        zetReservering(doel, reservering(doel) - uitReservering);

        return uitReservering;
    }

    /**
     * Geeft tokens terug, tot ten hoogste {@code maximum}, waarvan {@code naarReservering} in de eigen
     * reservering, tot {@code reserveringMaximum}; de rest komt in de gedeelde ruimte.
     */
    public void geefTerug(BudgetDoel doel, int aantal, int naarReservering, int maximum, int reserveringMaximum) {
        tokens = Math.min(tokens + aantal, maximum);
        zetReservering(doel, Math.min(reservering(doel) + naarReservering, reserveringMaximum));
    }

    private int reservering(BudgetDoel doel) {
        return doel == BudgetDoel.NAVRAAG ? navraagTokens : verzendTokens;
    }

    private void zetReservering(BudgetDoel doel, int waarde) {
        if (doel == BudgetDoel.NAVRAAG) {
            navraagTokens = waarde;
        } else {
            verzendTokens = waarde;
        }
    }

    public record Sleutel(String notifyService, OffsetDateTime tijdvak) {
    }
}
