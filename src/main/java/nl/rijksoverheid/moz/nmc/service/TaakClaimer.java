package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import nl.rijksoverheid.moz.nmc.domain.BudgetDoel;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Claimt taken voor een worker en werkt geclaimde taken bij. Een claimronde kiest de dienstverleners
 * met werk dat aan de beurt is en claimt per dienstverlener een deel van de batch, zodat een piek van
 * één dienstverlener de andere niet blokkeert; voor het verzenden en de navraag komen de tokens uit
 * het verzendbudget.
 * <p>
 * De claim is een eigen transactie die committet vóór de uitvoering. Elke wijziging daarna toetst
 * het claim-epoch en gooit {@link TaakVerlorenException} als een andere worker de taak inmiddels
 * heeft, zodat de transactie van de aanroeper terugrolt.
 */
@ApplicationScoped
public class TaakClaimer {

    private final TaakRepository taakRepository;
    private final Verzendbudget verzendbudget;
    private final Duration lease;

    public TaakClaimer(TaakRepository taakRepository, Verzendbudget verzendbudget,
                       @ConfigProperty(name = "nmc.taak.lease") Duration lease) {
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalStateException("nmc.taak.lease moet positief zijn, is " + lease);
        }

        this.taakRepository = taakRepository;
        this.verzendbudget = verzendbudget;
        this.lease = lease;
    }

    /**
     * Claimt tot {@code batch} taken van deze soort die aan de beurt zijn, verdeeld over de
     * dienstverleners met werk. Levert een lege lijst als er geen werk is of het budget op is; in dat
     * laatste geval blijft elke taak onaangeraakt en komt hij in een volgende ronde weer aan de beurt.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<Taak> claim(TaakSoort soort, int batch) {
        OffsetDateTime nu = OffsetDateTime.now(ZoneOffset.UTC);
        List<UUID> dienstverleners = taakRepository.dienstverlenersMetOpenWerk(soort, nu);

        if (dienstverleners.isEmpty()) {
            return List.of();
        }

        Optional<BudgetDoel> doel = soort.budgetDoel();
        int budget = doel.map(d -> verzendbudget.neem(d, batch)).orElse(batch);

        if (budget == 0) {
            Log.debugf("Claim van %s uitgesteld: verzendbudget voor dit tijdvak is op", soort);

            return List.of();
        }

        // Ceiling, zodat een kleine batch over veel dienstverleners toch iedereen aan bod laat komen.
        int perDienstverlener = Math.max(1, (budget + dienstverleners.size() - 1) / dienstverleners.size());
        OffsetDateTime leaseTot = nu.plus(lease);
        List<Taak> geclaimd = new ArrayList<>();

        for (UUID dvId : dienstverleners) {
            int ruimte = budget - geclaimd.size();

            if (ruimte <= 0) {
                break;
            }

            geclaimd.addAll(taakRepository.claim(soort, dvId, Math.min(perDienstverlener, ruimte), nu, leaseTot));
        }

        doel.ifPresent(d -> verzendbudget.geefTerug(budget - geclaimd.size()));

        return geclaimd;
    }

    /** Verlengt de lease met de geconfigureerde duur, gerekend vanaf nu. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void verleng(Taak taak) {
        eis(taakRepository.verlengLease(taak, OffsetDateTime.now(ZoneOffset.UTC).plus(lease)), taak);
    }

    /** Rondt de taak af in de transactie van de aanroeper, of een eigen als die er niet is. */
    @Transactional
    public void rondAf(Taak taak) {
        eis(taakRepository.rondAf(taak), taak);
    }

    @Transactional
    public void stelUit(Taak taak, OffsetDateTime due, boolean teltAlsPoging) {
        eis(taakRepository.stelUit(taak, due, teltAlsPoging), taak);
    }

    @Transactional
    public void markeerMislukt(Taak taak) {
        eis(taakRepository.markeerMislukt(taak), taak);
    }

    private static void eis(boolean geraakt, Taak taak) {
        if (!geraakt) {
            throw new TaakVerlorenException(taak);
        }
    }
}
