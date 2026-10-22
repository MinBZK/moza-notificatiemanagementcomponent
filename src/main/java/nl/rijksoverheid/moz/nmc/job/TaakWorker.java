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

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
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
 * {@code nmc_taken_mislukt}. Heropenen is een handmatige ingreep in de database: status op
 * {@code OPEN}, {@code pogingen} op 0 en {@code due} op nu.
 */
@Startup
@ApplicationScoped
public class TaakWorker {

    private static final Duration BASIS_WACHTTIJD = Duration.ofSeconds(30);
    private static final Duration MAX_WACHTTIJD = Duration.ofDays(1);

    private final TaakClaimer taakClaimer;
    private final TaakRepository taakRepository;
    private final Map<TaakSoort, TaakHandler> handlers = new EnumMap<>(TaakSoort.class);
    private final Clock clock;
    private final int batch;
    private final int maxPogingen;
    private final Duration metriekVerversing;
    private Map<TaakSoort, TaakRepository.TaakTelling> tellingen = Map.of();
    private long geteldOpNanos;
    private boolean geteld;

    public TaakWorker(TaakClaimer taakClaimer, TaakRepository taakRepository, Instance<TaakHandler> handlerBeans,
                      MeterRegistry meterRegistry, Clock clock,
                      @ConfigProperty(name = "nmc.taak.batch") int batch,
                      @ConfigProperty(name = "nmc.taak.max-pogingen") int maxPogingen,
                      @ConfigProperty(name = "nmc.taak.metriek-verversing") Duration metriekVerversing) {
        if (batch < 1 || maxPogingen < 1) {
            throw new IllegalStateException("nmc.taak.batch en nmc.taak.max-pogingen moeten 1 of hoger zijn");
        }

        this.taakClaimer = taakClaimer;
        this.taakRepository = taakRepository;
        this.clock = clock;
        this.batch = batch;
        this.maxPogingen = maxPogingen;
        this.metriekVerversing = metriekVerversing;

        for (TaakHandler handler : handlerBeans) {
            TaakHandler eerder = handlers.putIfAbsent(handler.taakSoort(), handler);

            if (eerder != null) {
                throw new IllegalStateException("Twee handlers voor taaksoort " + handler.taakSoort() + ": "
                        + eerder.getClass().getName() + " en " + handler.getClass().getName());
            }
        }

        // Een scrape leest alle gauges na elkaar; één telling per verversing bedient ze allemaal.
        for (TaakSoort taakSoort : TaakSoort.values()) {
            Gauge.builder("nmc.taken.mislukt", () -> telling(taakSoort).mislukt())
                    .description("Taken die na het maximum aantal pogingen op beheer wachten")
                    .tag("soort", taakSoort.name())
                    .register(meterRegistry);
            Gauge.builder("nmc.taken.achterstand", () -> telling(taakSoort).achterstand())
                    .description("Open taken die aan de beurt zijn")
                    .tag("soort", taakSoort.name())
                    .register(meterRegistry);
        }
    }

    // Eigen transactie: een scrape heeft geen transactie of requestcontext.
    private synchronized TaakRepository.TaakTelling telling(TaakSoort taakSoort) {
        long nu = System.nanoTime();

        if (!geteld || nu - geteldOpNanos >= metriekVerversing.toNanos()) {
            tellingen = QuarkusTransaction.requiringNew().call(() -> taakRepository.telPerSoort(OffsetDateTime.now(clock)));
            geteldOpNanos = nu;
            geteld = true;
        }

        return tellingen.getOrDefault(taakSoort, TaakRepository.TaakTelling.GEEN);
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
    // Package-private zodat een test een ronde direct kan draaien en het aantal geclaimde taken ziet; tests buiten
    // dit package gaan via TaakRonde.
    int verwerk(TaakSoort taakSoort) {
        TaakHandler handler = handlers.get(taakSoort);

        if (handler == null) {
            return 0;
        }

        List<Taak> taken;

        try {
            taken = taakClaimer.claim(taakSoort, batch);
        } catch (RuntimeException e) {
            // Eén regel zonder stacktrace: bij een databasestoring faalt elke soort op elke pod tegelijk.
            Log.warnf("Claimronde %s mislukt: %s", taakSoort, e);

            return 0;
        }

        for (Taak taak : taken) {
            voerUit(handler, taak);
        }

        return taken.size();
    }

    // Alleen een fout in de handler kost een poging. Een fout in de boekhouding ervoor of erna laat de
    // taak staan; hij komt terug zodra zijn lease verloopt.
    private void voerUit(TaakHandler handler, Taak taak) {
        try {
            // De batch deelt één lease; een taak waar de worker pas laat aan toekomt, kan intussen door
            // een andere worker geclaimd zijn en wordt dan overgeslagen.
            taakClaimer.verleng(taak);
        } catch (TaakVerlorenException e) {
            Log.warn(e.getMessage());

            return;
        } catch (RuntimeException e) {
            Log.warnf(e, "Lease van taak %s/%d kon niet worden verlengd", taak.getTaakSoort(), taak.getId());

            return;
        }

        TaakUitkomst uitkomst;

        try {
            uitkomst = handler.voerUit(taak, () -> taakClaimer.verleng(taak));
        } catch (TaakVerlorenException e) {
            // Een andere worker heeft de taak; wat deze worker deed is teruggerold of blijft zonder gevolg.
            Log.warn(e.getMessage());

            return;
        } catch (RuntimeException e) {
            faal(handler, taak, e);

            return;
        }

        try {
            switch (uitkomst) {
                case TaakUitkomst.Afgerond() -> taakClaimer.rondAf(taak);
                case TaakUitkomst.Uitgesteld u when u.teltAlsPoging() && taak.getPogingen() + 1 >= maxPogingen ->
                        verwerkUitputting(handler, taak, null);
                case TaakUitkomst.Uitgesteld u -> taakClaimer.stelUit(taak, u.due(), u.teltAlsPoging());
                case TaakUitkomst.Herpland h -> taakClaimer.herplanNaSucces(taak, h.due());
                case TaakUitkomst.AlAfgerond() -> {
                    // De handler heeft de rij in zijn eigen transactie afgerond.
                }
            }
        } catch (TaakVerlorenException e) {
            Log.warn(e.getMessage());
        } catch (RuntimeException e) {
            // De handler draait dan opnieuw; zie het contract van TaakHandler.
            Log.errorf(e, "Taak %s/%d is uitgevoerd maar kon niet worden bijgewerkt", taak.getTaakSoort(), taak.getId());
        }
    }

    // Een fout in de handler kost een poging. De wachttijd loopt op met het aantal pogingen, zodat
    // een taak die steeds faalt niet elke ronde opnieuw aan de beurt is.
    private void faal(TaakHandler handler, Taak taak, RuntimeException fout) {
        int pogingen = taak.getPogingen() + 1;

        try {
            if (pogingen >= maxPogingen) {
                verwerkUitputting(handler, taak, fout);
            } else {
                OffsetDateTime due = OffsetDateTime.now(clock).plus(wachttijd(pogingen));
                taakClaimer.stelUit(taak, due, true);
                Log.warnf(fout, "Taak %s/%d mislukt (poging %d van %d), opnieuw op %s",
                        taak.getTaakSoort(), taak.getId(), pogingen, maxPogingen, due);
            }
        } catch (TaakVerlorenException e) {
            Log.warn(e.getMessage());
        } catch (RuntimeException e) {
            // De rest van de batch gaat door; deze taak komt terug zodra zijn lease verloopt.
            Log.errorf(e, "Taak %s/%d kon na een fout niet worden bijgewerkt", taak.getTaakSoort(), taak.getId());
        }
    }

    // De handler krijgt de kans de uitputting af te handelen, in dezelfde transactie als de taakrij.
    private void verwerkUitputting(TaakHandler handler, Taak taak, RuntimeException fout) {
        if (handler.periodiek()) {
            OffsetDateTime due = OffsetDateTime.now(clock).plus(wachttijd(maxPogingen));
            taakClaimer.stelUit(taak, due, false);
            Log.errorf(fout, "Periodieke taak %s/%d faalt na %d pogingen; opnieuw op %s",
                    taak.getTaakSoort(), taak.getId(), maxPogingen, due);

            return;
        }

        boolean afgehandeld = QuarkusTransaction.requiringNew().call(() -> {
            if (handler.handelUitputtingAf(taak)) {
                taakClaimer.rondAf(taak);

                return true;
            }

            taakClaimer.markeerMislukt(taak);

            return false;
        });

        if (afgehandeld) {
            Log.warnf(fout, "Taak %s/%d uitgeput na %d pogingen; door de handler afgehandeld",
                    taak.getTaakSoort(), taak.getId(), maxPogingen);
        } else {
            Log.errorf(fout, "Taak %s/%d na %d pogingen op mislukt gezet; wacht op beheer",
                    taak.getTaakSoort(), taak.getId(), maxPogingen);
        }
    }

    // Verdubbelt per poging, begrensd op een dag zodat een hoog maximum niet overloopt.
    private static Duration wachttijd(int pogingen) {
        Duration wachttijd = BASIS_WACHTTIJD.multipliedBy(1L << Math.min(pogingen - 1, 20));

        return wachttijd.compareTo(MAX_WACHTTIJD) > 0 ? MAX_WACHTTIJD : wachttijd;
    }
}
