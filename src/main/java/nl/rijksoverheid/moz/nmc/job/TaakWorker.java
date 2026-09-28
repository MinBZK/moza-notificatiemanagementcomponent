package nl.rijksoverheid.moz.nmc.job;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.domain.TaakStatus;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.service.TaakClaimer;
import nl.rijksoverheid.moz.nmc.service.TaakHandler;
import nl.rijksoverheid.moz.nmc.service.TaakUitkomst;
import nl.rijksoverheid.moz.nmc.service.TaakVerlorenException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * De worker-lus: per soort een periodieke ronde die een batch claimt en elke taak aan de
 * {@link TaakHandler} van die soort geeft. Zonder handler voor een soort doet de ronde niets.
 * {@code concurrentExecution = SKIP} geldt per JVM; meerdere pods verdelen het werk via de claim.
 * <p>
 * Een handler die gooit kost de taak een poging en een uitstel met oplopende wachttijd; na
 * {@code nmc.taak.max-pogingen} staat de taak op mislukt en telt hij in de metriek
 * {@code nmc_taken_mislukt}, tot beheer hem heropent.
 */
@Startup
@ApplicationScoped
public class TaakWorker {

    private static final Duration BASIS_WACHTTIJD = Duration.ofSeconds(30);

    private final TaakClaimer taakClaimer;
    private final TaakRepository taakRepository;
    private final Map<TaakSoort, TaakHandler> handlers = new EnumMap<>(TaakSoort.class);
    private final int batch;
    private final int maxPogingen;

    public TaakWorker(TaakClaimer taakClaimer, TaakRepository taakRepository, Instance<TaakHandler> handlerBeans,
                      MeterRegistry meterRegistry,
                      @ConfigProperty(name = "nmc.taak.batch") int batch,
                      @ConfigProperty(name = "nmc.taak.max-pogingen") int maxPogingen) {
        if (batch < 1 || maxPogingen < 1) {
            throw new IllegalStateException("nmc.taak.batch en nmc.taak.max-pogingen moeten 1 of hoger zijn");
        }

        this.taakClaimer = taakClaimer;
        this.taakRepository = taakRepository;
        this.batch = batch;
        this.maxPogingen = maxPogingen;

        for (TaakHandler handler : handlerBeans) {
            TaakHandler eerder = handlers.putIfAbsent(handler.soort(), handler);

            if (eerder != null) {
                throw new IllegalStateException("Twee handlers voor taaksoort " + handler.soort() + ": "
                        + eerder.getClass().getName() + " en " + handler.getClass().getName());
            }
        }

        // Eigen transactie per meting: een scrape heeft geen transactie of requestcontext.
        for (TaakSoort soort : TaakSoort.values()) {
            Gauge.builder("nmc.taken.mislukt", () -> QuarkusTransaction.requiringNew()
                            .call(() -> taakRepository.telMetStatus(soort, TaakStatus.MISLUKT)))
                    .description("Taken die na het maximum aantal pogingen op beheer wachten")
                    .tag("soort", soort.name())
                    .register(meterRegistry);
            Gauge.builder("nmc.taken.achterstand", () -> QuarkusTransaction.requiringNew()
                            .call(() -> taakRepository.telAchterstand(soort, OffsetDateTime.now(ZoneOffset.UTC))))
                    .description("Open taken die aan de beurt zijn")
                    .tag("soort", soort.name())
                    .register(meterRegistry);
        }
    }

    @Scheduled(identity = "taak-verzenden", every = "{nmc.taak.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void verzenden() {
        verwerk(TaakSoort.VERZENDEN);
    }

    @Scheduled(identity = "taak-receipt-verwerken", every = "{nmc.taak.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void receiptVerwerken() {
        verwerk(TaakSoort.RECEIPT_VERWERKEN);
    }

    @Scheduled(identity = "taak-reconcilieren", every = "{nmc.taak.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reconcilieren() {
        verwerk(TaakSoort.RECONCILIEREN);
    }

    @Scheduled(identity = "taak-bezorging-vaststellen", every = "{nmc.taak.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void bezorgingVaststellen() {
        verwerk(TaakSoort.BEZORGING_VASTSTELLEN);
    }

    @Scheduled(identity = "taak-terugkoppelen", every = "{nmc.taak.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void terugkoppelen() {
        verwerk(TaakSoort.TERUGKOPPELEN);
    }

    @Scheduled(identity = "taak-ongeldig-melden", every = "{nmc.taak.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void ongeldigMelden() {
        verwerk(TaakSoort.ONGELDIG_MELDEN);
    }

    @Scheduled(identity = "taak-wissen", every = "{nmc.taak.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void wissen() {
        verwerk(TaakSoort.WISSEN);
    }

    @Scheduled(identity = "taak-controle", every = "{nmc.taak.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void controle() {
        verwerk(TaakSoort.CONTROLE);
    }

    @Scheduled(identity = "taak-onderhoud", every = "{nmc.taak.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void onderhoud() {
        verwerk(TaakSoort.ONDERHOUD);
    }

    /**
     * Eén ronde voor deze soort: claimt een batch en voert elke taak uit.
     *
     * @return het aantal geclaimde taken
     */
    public int verwerk(TaakSoort soort) {
        TaakHandler handler = handlers.get(soort);

        if (handler == null) {
            return 0;
        }

        List<Taak> taken = taakClaimer.claim(soort, batch);

        for (Taak taak : taken) {
            voerUit(handler, taak);
        }

        return taken.size();
    }

    private void voerUit(TaakHandler handler, Taak taak) {
        try {
            TaakUitkomst uitkomst = handler.voerUit(taak, () -> taakClaimer.verleng(taak));

            switch (uitkomst) {
                case TaakUitkomst.Afgerond a -> taakClaimer.rondAf(taak);
                case TaakUitkomst.Uitgesteld u -> taakClaimer.stelUit(taak, u.due(), u.teltAlsPoging());
                case TaakUitkomst.AlAfgerond a -> {
                    // De handler heeft de rij in zijn eigen transactie afgerond.
                }
            }
        } catch (TaakVerlorenException e) {
            // Een andere worker heeft de taak; wat deze worker deed is teruggerold of blijft zonder gevolg.
            Log.warn(e.getMessage());
        } catch (RuntimeException e) {
            faal(taak, e);
        }
    }

    // Een fout in de handler kost een poging. De wachttijd loopt op met het aantal pogingen, zodat
    // een taak die steeds faalt niet elke ronde opnieuw aan de beurt is.
    private void faal(Taak taak, RuntimeException fout) {
        int pogingen = taak.getPogingen() + 1;

        try {
            if (pogingen >= maxPogingen) {
                taakClaimer.markeerMislukt(taak);
                Log.errorf(fout, "Taak %s/%d na %d pogingen op mislukt gezet; wacht op beheer",
                        taak.getSoort(), taak.getId(), pogingen);
            } else {
                OffsetDateTime due = OffsetDateTime.now(ZoneOffset.UTC).plus(BASIS_WACHTTIJD.multipliedBy(1L << (pogingen - 1)));
                taakClaimer.stelUit(taak, due, true);
                Log.warnf(fout, "Taak %s/%d mislukt (poging %d van %d), opnieuw op %s",
                        taak.getSoort(), taak.getId(), pogingen, maxPogingen, due);
            }
        } catch (TaakVerlorenException e) {
            Log.warn(e.getMessage());
        }
    }
}
