package nl.rijksoverheid.moz.nmc.service.taak;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import nl.rijksoverheid.moz.nmc.config.VerzendbudgetConfig;
import nl.rijksoverheid.moz.nmc.domain.BudgetDoel;
import nl.rijksoverheid.moz.nmc.domain.VerzendbudgetTijdvak;
import nl.rijksoverheid.moz.nmc.repository.VerzendbudgetRepository;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Tokens per tijdvak van een minuut, als rij in {@code verzendbudget}. De eerste
 * claim in een tijdvak maakt de rij aan met het volledige budget; elke claim neemt er tokens af.
 * Verzenden en navraag hebben elk een reservering die de ander laat staan; de rest van het tijdvak is
 * voor wie het eerst claimt.
 * <p>
 * Loopt in de transactie van de claim: de rij van het tijdvak blijft tot de commit vergrendeld, zodat
 * twee gelijktijdige claims elkaars tokens niet dubbel nemen.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class Verzendbudget {

    private final VerzendbudgetRepository verzendbudgetRepository;
    private final Clock clock;
    private final int tokensPerMinuut;
    private final int verzendReservering;
    private final int navraagReservering;

    public Verzendbudget(VerzendbudgetRepository verzendbudgetRepository, Clock clock, VerzendbudgetConfig config) {
        this.verzendbudgetRepository = verzendbudgetRepository;
        this.clock = clock;
        this.tokensPerMinuut = config.tokensPerMinuut();
        this.verzendReservering = config.verzendReservering();
        this.navraagReservering = config.navraagReservering();
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
            return new Genomen(0, tijdvak, doel, 0);
        }

        VerzendbudgetTijdvak budget = verzendbudgetRepository.vergrendel(tijdvak, tokensPerMinuut,
                verzendReservering, navraagReservering);
        int genomen = Math.min(gevraagd, budget.beschikbaar(doel));
        int uitReservering = budget.neem(doel, genomen);

        return new Genomen(genomen, tijdvak, doel, uitReservering);
    }

    /**
     * Geeft tokens terug die een claim wel nam maar niet gebruikte, binnen dezelfde transactie en naar
     * het tijdvak waaruit ze kwamen: eerst de gedeelde ruimte, dan de eigen reservering, zodat de ander
     * de ruimte houdt die de claim per saldo niet gebruikte. Is dat tijdvak voorbij, dan vervallen ze met
     * het tijdvak.
     */
    public void geefTerug(Genomen genomen, int aantal) {
        int terug = Math.min(aantal, genomen.aantal());

        if (terug <= 0 || !genomen.tijdvak().equals(tijdvak())) {
            return;
        }

        int uitGedeeld = genomen.aantal() - genomen.uitReservering();
        int reservering = genomen.doel() == BudgetDoel.NAVRAAG ? navraagReservering : verzendReservering;
        verzendbudgetRepository.vergrendel(genomen.tijdvak(), tokensPerMinuut, verzendReservering,
                        navraagReservering)
                .geefTerug(genomen.doel(), terug, Math.max(0, terug - uitGedeeld), tokensPerMinuut, reservering);
    }

    /**
     * Verwijdert de rijen van tijdvakken vóór {@code grens}; hun tokens zijn met het tijdvak vervallen.
     *
     * @return het aantal verwijderde rijen
     */
    public int verwijderTijdvakkenOuderDan(OffsetDateTime grens) {
        return verzendbudgetRepository.verwijderTijdvakkenOuderDan(grens);
    }

    /**
     * Het aantal tokens dat een claim nam, uit welk tijdvak en voor welk doel, en hoeveel ervan uit de
     * eigen reservering kwam.
     */
    public record Genomen(int aantal, OffsetDateTime tijdvak, BudgetDoel doel, int uitReservering) {
    }

    private OffsetDateTime tijdvak() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
    }
}
