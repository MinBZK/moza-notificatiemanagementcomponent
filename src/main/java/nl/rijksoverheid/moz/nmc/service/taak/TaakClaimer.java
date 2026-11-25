package nl.rijksoverheid.moz.nmc.service.taak;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import nl.rijksoverheid.moz.nmc.config.TaakConfig;
import nl.rijksoverheid.moz.nmc.domain.BudgetDoel;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;

/**
 * Claimt taken voor een worker en werkt geclaimde taken bij. Een claimronde kiest de dienstverleners
 * met werk dat aan de beurt is en claimt per dienstverlener een deel van de batch, zodat een piek van
 * één dienstverlener de andere niet blokkeert; voor het verzenden en de navraag komen de tokens uit
 * het verzendbudget.
 * <p>
 * De claim committet vóór de uitvoering. De tokens nemen, de taken claimen en de ongebruikte tokens
 * teruggeven zijn drie transacties, zodat de rij van het verzendbudget, die alle pods delen, niet
 * vergrendeld is tijdens het claimen. Elke wijziging daarna toetst het claim-epoch en gooit
 * {@link TaakVerlorenException} als een andere worker de taak inmiddels heeft, zodat de transactie van
 * de aanroeper terugrolt.
 */
@ApplicationScoped
public class TaakClaimer {

    /** Zo lang mag de langste stap van de onderhoudstaak duren: het aanhangen van een eventpartitie. */
    public static final Duration LANGSTE_ONDERHOUDSSTAP = Duration.ofMinutes(5);

    // De lease van de onderhoudstaak is langer dan haar langste stap; de marge voorkomt dat de lease
    // afloopt op het moment dat de database die stap afbreekt.
    private static final Duration ONDERHOUD_LEASE = LANGSTE_ONDERHOUDSSTAP.plusMinutes(1);

    private final TaakRepository taakRepository;
    private final Verzendbudget verzendbudget;
    private final Clock clock;
    private final Duration lease;

    public TaakClaimer(TaakRepository taakRepository, Verzendbudget verzendbudget, Clock clock, TaakConfig config) {
        this.taakRepository = taakRepository;
        this.verzendbudget = verzendbudget;
        this.clock = clock;
        this.lease = config.lease();
    }

    /**
     * Claimt tot {@code batch} taken van deze soort die aan de beurt zijn, verdeeld over de
     * dienstverleners met werk. Levert een lege lijst als er geen werk is of het budget op is; in dat
     * laatste geval blijft elke taak onaangeraakt en komt hij in een volgende ronde weer aan de beurt.
     */
    public List<Taak> claim(TaakSoort taakSoort, int batch) {
        OffsetDateTime nu = OffsetDateTime.now(clock);
        Optional<BudgetDoel> doel = taakSoort.budgetDoel();
        Voorbereiding voorbereiding = QuarkusTransaction.requiringNew().call(() -> {
            List<UUID> metWerk = taakRepository.dienstverlenersMetOpenWerk(taakSoort, nu);

            return new Voorbereiding(
                    metWerk, metWerk.isEmpty() ? Optional.empty() : doel.map(d -> verzendbudget.neem(d, batch)));
        });
        List<UUID> dienstverleners = voorbereiding.dienstverleners();

        if (dienstverleners.isEmpty()) {
            return List.of();
        }

        Optional<Verzendbudget.Genomen> genomen = voorbereiding.genomen();
        int budget = genomen.map(Verzendbudget.Genomen::aantal).orElse(batch);

        if (budget == 0) {
            Log.debugf("Claim van %s uitgesteld: verzendbudget voor dit tijdvak is op", taakSoort);

            return List.of();
        }

        List<Taak> geclaimd;

        try {
            geclaimd = QuarkusTransaction.requiringNew().call(() -> claimTaken(taakSoort, dienstverleners, budget, nu));
        } catch (RuntimeException e) {
            geefTerug(genomen, budget);

            throw e;
        }

        geefTerug(genomen, budget - geclaimd.size());

        return geclaimd;
    }

    private record Voorbereiding(List<UUID> dienstverleners, Optional<Verzendbudget.Genomen> genomen) {}

    // De taken zijn al geclaimd of de claim is al mislukt; een teruggave die niet lukt verandert daar
    // niets aan. De tokens vervallen dan met het tijdvak.
    private void geefTerug(Optional<Verzendbudget.Genomen> genomen, int aantal) {
        if (genomen.isEmpty() || aantal <= 0) {
            return;
        }

        try {
            QuarkusTransaction.requiringNew().run(() -> verzendbudget.geefTerug(genomen.get(), aantal));
        } catch (RuntimeException e) {
            Log.warnf(
                    e,
                    "%d ongebruikte token(s) voor %s niet teruggegeven aan het verzendbudget",
                    aantal,
                    genomen.get().doel());
        }
    }

    private List<Taak> claimTaken(TaakSoort taakSoort, List<UUID> dienstverleners, int budget, OffsetDateTime nu) {
        // Ceiling, zodat een kleine batch over veel dienstverleners toch iedereen aan bod laat komen.
        int perDienstverlener = Math.max(1, (budget + dienstverleners.size() - 1) / dienstverleners.size());
        OffsetDateTime leaseTot = nu.plus(leaseVoor(taakSoort));
        List<Taak> geclaimd = new ArrayList<>();

        List<UUID> metMeerWerk = new ArrayList<>();

        for (UUID dvId : dienstverleners) {
            int ruimte = budget - geclaimd.size();

            if (ruimte <= 0) {
                break;
            }

            int gevraagd = Math.min(perDienstverlener, ruimte);
            List<Taak> taken = taakRepository.claim(taakSoort, dvId, gevraagd, nu, leaseTot);
            geclaimd.addAll(taken);

            if (taken.size() == gevraagd) {
                metMeerWerk.add(dvId);
            }
        }

        // Wat een dienstverlener met weinig werk liet liggen, gaat naar wie zijn aandeel vol had. De
        // taken uit de eerste ronde hebben al een lease, dus de claim slaat ze over.
        for (UUID dvId : metMeerWerk) {
            int ruimte = budget - geclaimd.size();

            if (ruimte <= 0) {
                break;
            }

            geclaimd.addAll(taakRepository.claim(taakSoort, dvId, ruimte, nu, leaseTot));
        }

        return geclaimd;
    }

    private Duration leaseVoor(TaakSoort taakSoort) {
        return taakSoort == TaakSoort.ONDERHOUD && lease.compareTo(ONDERHOUD_LEASE) < 0 ? ONDERHOUD_LEASE : lease;
    }

    /** Verlengt de lease met de duur voor deze soort, gerekend vanaf nu. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void verleng(Taak taak) {
        eis(taakRepository.verlengLease(taak, OffsetDateTime.now(clock).plus(leaseVoor(taak.getTaakSoort()))), taak);
    }

    /**
     * Verlengt de lease in de transactie van de aanroeper, zodat die alleen commit zolang deze worker de
     * taak heeft.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public void eisClaim(Taak taak) {
        eis(taakRepository.verlengLease(taak, OffsetDateTime.now(clock).plus(leaseVoor(taak.getTaakSoort()))), taak);
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

    /**
     * Laat de taak op {@code due} weer draaien en zet de pogingen op 0, zodat alleen opeenvolgende
     * fouten tellen voor het maximum.
     *
     * @throws TaakVerlorenException als de taak inmiddels bij een andere worker ligt
     */
    @Transactional
    public void herplanNaSucces(Taak taak, OffsetDateTime due) {
        eis(taakRepository.herplanNaSucces(taak, due), taak);
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
