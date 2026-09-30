package nl.rijksoverheid.moz.nmc.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import nl.rijksoverheid.moz.nmc.domain.BudgetDoel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Tokens per Notify-service per tijdvak van een minuut, als rij in {@code verzendbudget}. De eerste
 * claim in een tijdvak maakt de rij aan met het volledige budget; elke claim neemt er tokens af. Een
 * vast aandeel ({@code navraag_tokens}) is voor de navraag: verzenden laat het staan en de navraag
 * neemt niet meer dan dat. Er
 * is één Notify-service; de sleutel blijft de service, zodat een tweede later een tweede rij is.
 * <p>
 * Loopt in de transactie van de claim: de {@code INSERT ... ON CONFLICT DO UPDATE} vergrendelt de
 * rij, zodat twee gelijktijdige claims elkaars tokens niet dubbel nemen.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class Verzendbudget {

    private final EntityManager entityManager;
    private final Clock klok;
    private final String notifyService;
    private final int tokensPerMinuut;
    private final int navraagReservering;

    public Verzendbudget(EntityManager entityManager, Clock klok,
                         @ConfigProperty(name = "nmc.verzendbudget.notify-service") String notifyService,
                         @ConfigProperty(name = "nmc.verzendbudget.tokens-per-minuut") int tokensPerMinuut,
                         @ConfigProperty(name = "nmc.verzendbudget.navraag-reservering") int navraagReservering) {
        if (tokensPerMinuut < 1) {
            throw new IllegalStateException("nmc.verzendbudget.tokens-per-minuut moet 1 of hoger zijn, is " + tokensPerMinuut);
        }

        if (navraagReservering < 1 || navraagReservering >= tokensPerMinuut) {
            throw new IllegalStateException("nmc.verzendbudget.navraag-reservering moet tussen 1 en "
                    + tokensPerMinuut + " liggen, is " + navraagReservering);
        }

        this.entityManager = entityManager;
        this.klok = klok;
        this.notifyService = notifyService;
        this.tokensPerMinuut = tokensPerMinuut;
        this.navraagReservering = navraagReservering;
    }

    /**
     * Neemt tot {@code gevraagd} tokens uit het lopende tijdvak.
     *
     * @return het aantal genomen tokens en het tijdvak waaruit ze komen; een teruggave gaat naar
     *         dat tijdvak, ook als de minuut inmiddels om is
     */
    public Genomen neem(BudgetDoel doel, int gevraagd) {
        OffsetDateTime tijdvak = tijdvak();

        if (gevraagd <= 0) {
            return new Genomen(0, tijdvak, doel);
        }

        Object[] stand = (Object[]) entityManager.createNativeQuery(
                        "INSERT INTO verzendbudget (notify_service, tijdvak, tokens, navraag_tokens) VALUES (?1, ?2, ?3, ?4) "
                                + "ON CONFLICT (notify_service, tijdvak) DO UPDATE SET tokens = verzendbudget.tokens "
                                + "RETURNING tokens, navraag_tokens")
                .setParameter(1, notifyService)
                .setParameter(2, tijdvak)
                .setParameter(3, tokensPerMinuut)
                .setParameter(4, navraagReservering)
                .getSingleResult();
        int beschikbaar = ((Number) stand[0]).intValue();
        int navraag = ((Number) stand[1]).intValue();

        int toegestaan = doel == BudgetDoel.NAVRAAG ? navraag : beschikbaar - navraag;
        int genomen = Math.min(gevraagd, toegestaan);

        if (genomen > 0) {
            entityManager.createNativeQuery("UPDATE verzendbudget SET tokens = tokens - ?3, navraag_tokens = navraag_tokens - ?4 "
                            + "WHERE notify_service = ?1 AND tijdvak = ?2")
                    .setParameter(1, notifyService)
                    .setParameter(2, tijdvak)
                    .setParameter(3, genomen)
                    .setParameter(4, doel == BudgetDoel.NAVRAAG ? genomen : 0)
                    .executeUpdate();
        }

        return new Genomen(genomen, tijdvak, doel);
    }

    /**
     * Geeft tokens terug die een claim wel nam maar niet gebruikte, binnen dezelfde transactie en naar
     * het tijdvak en het aandeel waaruit ze kwamen. Is dat tijdvak voorbij, dan vervallen ze met het tijdvak.
     */
    public void geefTerug(Genomen genomen, int aantal) {
        if (aantal <= 0 || !genomen.tijdvak().equals(tijdvak())) {
            return;
        }

        int terug = Math.min(aantal, genomen.aantal());
        entityManager.createNativeQuery("UPDATE verzendbudget SET tokens = LEAST(tokens + ?3, ?4), "
                        + "navraag_tokens = LEAST(navraag_tokens + ?5, ?6) WHERE notify_service = ?1 AND tijdvak = ?2")
                .setParameter(1, notifyService)
                .setParameter(2, genomen.tijdvak())
                .setParameter(3, terug)
                .setParameter(4, tokensPerMinuut)
                .setParameter(5, genomen.doel() == BudgetDoel.NAVRAAG ? terug : 0)
                .setParameter(6, navraagReservering)
                .executeUpdate();
    }

    /** Het aantal tokens dat een claim nam, uit welk tijdvak en voor welk doel. */
    public record Genomen(int aantal, OffsetDateTime tijdvak, BudgetDoel doel) {
    }

    private OffsetDateTime tijdvak() {
        return OffsetDateTime.now(klok).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
    }
}
