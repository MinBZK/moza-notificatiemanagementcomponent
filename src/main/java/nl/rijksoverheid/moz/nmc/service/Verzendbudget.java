package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.runtime.Startup;
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
 * claim in een tijdvak maakt de rij aan met het volledige budget; elke claim neemt er tokens af. Er
 * is één Notify-service; de sleutel blijft de service, zodat een tweede later een tweede rij is.
 * <p>
 * Loopt in de transactie van de claim: de {@code INSERT ... ON CONFLICT DO UPDATE} vergrendelt de
 * rij, zodat twee gelijktijdige claims elkaars tokens niet dubbel nemen.
 */
@Startup
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

        if (navraagReservering < 0 || navraagReservering >= tokensPerMinuut) {
            throw new IllegalStateException("nmc.verzendbudget.navraag-reservering moet tussen 0 en "
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
            return new Genomen(0, tijdvak);
        }

        int beschikbaar = ((Number) entityManager.createNativeQuery(
                        "INSERT INTO verzendbudget (notify_service, tijdvak, tokens) VALUES (?1, ?2, ?3) "
                                + "ON CONFLICT (notify_service, tijdvak) DO UPDATE SET tokens = verzendbudget.tokens "
                                + "RETURNING tokens")
                .setParameter(1, notifyService)
                .setParameter(2, tijdvak)
                .setParameter(3, tokensPerMinuut)
                .getSingleResult()).intValue();

        int toegestaan = doel == BudgetDoel.NAVRAAG ? beschikbaar : Math.max(0, beschikbaar - navraagReservering);
        int genomen = Math.min(gevraagd, toegestaan);

        if (genomen > 0) {
            entityManager.createNativeQuery("UPDATE verzendbudget SET tokens = tokens - ?3 "
                            + "WHERE notify_service = ?1 AND tijdvak = ?2")
                    .setParameter(1, notifyService)
                    .setParameter(2, tijdvak)
                    .setParameter(3, genomen)
                    .executeUpdate();
        }

        return new Genomen(genomen, tijdvak);
    }

    /**
     * Geeft tokens terug die een claim wel nam maar niet gebruikte, binnen dezelfde transactie en naar
     * het tijdvak waaruit ze kwamen. Is dat tijdvak voorbij, dan vervallen ze met het tijdvak.
     */
    public void geefTerug(Genomen genomen, int aantal) {
        if (aantal <= 0 || !genomen.tijdvak().equals(tijdvak())) {
            return;
        }

        entityManager.createNativeQuery("UPDATE verzendbudget SET tokens = LEAST(tokens + ?3, ?4) "
                        + "WHERE notify_service = ?1 AND tijdvak = ?2")
                .setParameter(1, notifyService)
                .setParameter(2, genomen.tijdvak())
                .setParameter(3, Math.min(aantal, genomen.aantal()))
                .setParameter(4, tokensPerMinuut)
                .executeUpdate();
    }

    /** Het aantal tokens dat een claim nam, en het tijdvak waaruit. */
    public record Genomen(int aantal, OffsetDateTime tijdvak) {
    }

    private OffsetDateTime tijdvak() {
        return OffsetDateTime.now(klok).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
    }
}
