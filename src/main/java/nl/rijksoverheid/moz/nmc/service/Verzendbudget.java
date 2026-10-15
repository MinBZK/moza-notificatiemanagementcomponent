package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import nl.rijksoverheid.moz.nmc.domain.BudgetDoel;
import nl.rijksoverheid.moz.nmc.domain.VerzendbudgetTijdvak;
import nl.rijksoverheid.moz.nmc.repository.VerzendbudgetRepository;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Tokens per Notify-service per tijdvak van een minuut, als rij in {@code verzendbudget}. De eerste
 * claim in een tijdvak maakt de rij aan met het volledige budget; elke claim neemt er tokens af. Een
 * vast aandeel is voor de navraag: verzenden laat het staan en de navraag neemt niet meer dan dat. Er
 * is één Notify-service; de sleutel blijft de service, zodat een tweede later een tweede rij is.
 * <p>
 * Loopt in de transactie van de claim: de rij van het tijdvak blijft tot de commit vergrendeld, zodat
 * twee gelijktijdige claims elkaars tokens niet dubbel nemen.
 */
@Startup
@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class Verzendbudget {

    private final VerzendbudgetRepository verzendbudgetRepository;
    private final Clock clock;
    private final String notifyService;
    private final int tokensPerMinuut;
    private final int navraagReservering;

    public Verzendbudget(VerzendbudgetRepository verzendbudgetRepository, Clock clock,
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

        this.verzendbudgetRepository = verzendbudgetRepository;
        this.clock = clock;
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

        VerzendbudgetTijdvak budget = verzendbudgetRepository.vergrendel(notifyService, tijdvak, tokensPerMinuut,
                navraagReservering);
        int genomen = Math.min(gevraagd, budget.beschikbaar(doel));
        budget.neem(doel, genomen);

        return new Genomen(genomen, tijdvak, doel);
    }

    /**
     * Geeft tokens terug die een claim wel nam maar niet gebruikte, binnen dezelfde transactie en naar
     * het tijdvak en het aandeel waaruit ze kwamen. Is dat tijdvak voorbij, dan vervallen ze met het tijdvak.
     */
    public void geefTerug(Genomen genomen, int aantal) {
        int terug = Math.min(aantal, genomen.aantal());

        if (terug <= 0 || !genomen.tijdvak().equals(tijdvak())) {
            return;
        }

        verzendbudgetRepository.vergrendel(notifyService, genomen.tijdvak(), tokensPerMinuut, navraagReservering)
                .geefTerug(genomen.doel(), terug, tokensPerMinuut, navraagReservering);
    }

    /** Het aantal tokens dat een claim nam, uit welk tijdvak en voor welk doel. */
    public record Genomen(int aantal, OffsetDateTime tijdvak, BudgetDoel doel) {
    }

    private OffsetDateTime tijdvak() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
    }
}
