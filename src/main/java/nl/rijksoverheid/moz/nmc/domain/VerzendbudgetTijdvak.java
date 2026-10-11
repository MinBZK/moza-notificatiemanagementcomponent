package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Het verzendbudget van één Notify-service in één tijdvak van een minuut. Wijzig alleen een rij die met
 * een rijlock is gelezen, zodat twee gelijktijdige claims elkaars tokens niet dubbel nemen.
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

    protected VerzendbudgetTijdvak() {
        // Voor JPA
    }

    public int getTokens() {
        return tokens;
    }

    public void neem(int aantal) {
        if (aantal < 0 || aantal > tokens) {
            throw new IllegalArgumentException("Kan geen " + aantal + " tokens nemen, er zijn er " + tokens);
        }

        tokens -= aantal;
    }

    /** Geeft tokens terug, tot ten hoogste {@code maximum}. */
    public void geefTerug(int aantal, int maximum) {
        tokens = Math.min(tokens + aantal, maximum);
    }

    public record Sleutel(String notifyService, OffsetDateTime tijdvak) {
    }
}
