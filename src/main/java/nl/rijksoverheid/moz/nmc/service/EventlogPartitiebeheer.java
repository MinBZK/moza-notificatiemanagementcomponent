package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.repository.EventPartitie;
import nl.rijksoverheid.moz.nmc.repository.EventPartitieRepository;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Houdt de bereikpartities van het eventlog bij. Een nieuwe partitie begint boven de transactie-ids die
 * al zijn uitgedeeld, zodat de default-partitie nooit rijen in het nieuwe bereik heeft; wat toch buiten
 * de bereiken valt (de onderhoudstaak liep achter) blijft in de default-partitie en wordt daar per rij
 * opgeruimd.
 * <p>
 * Een partitie gaat pas weg als ze helemaal onder het watermerk ligt, haar jongste event ouder is dan de
 * bewaartermijn van het afleverbewijs, geen geldige bevestiging of leverpositie erin of eronder wijst en
 * de default-partitie geen oudere events meer heeft: anders leest een vervallen cursor over het gat heen.
 */
@Startup
@ApplicationScoped
public class EventlogPartitiebeheer {

    // Het aanhangen leest de default-partitie door; met de timeouts van de databaserol (V16) zou dat bij een
    // volle default-partitie elke ronde mislukken en er nooit meer een partitie bij komen. Zolang het duurt,
    // staat de feed stil op het watermerk van deze transactie.
    private static final Duration AANHANG_TIMEOUT = Duration.ofMinutes(5);

    private final EventPartitieRepository repository;
    private final Opruimtermijnen opruimtermijnen;
    private final long omvang;
    private final long marge;
    private final Duration lockTimeout;
    private final int epoch;

    public EventlogPartitiebeheer(EventPartitieRepository repository, Opruimtermijnen opruimtermijnen,
                          @ConfigProperty(name = "nmc.onderhoud.partitie-omvang") long omvang,
                          @ConfigProperty(name = "nmc.onderhoud.partitie-marge") long marge,
                          @ConfigProperty(name = "nmc.onderhoud.lock-timeout") Duration lockTimeout,
                          @ConfigProperty(name = "nmc.feed.cluster-epoch") int epoch) {
        if (omvang < 1) {
            throw new IllegalStateException("nmc.onderhoud.partitie-omvang moet 1 of hoger zijn, is " + omvang);
        }

        if (marge < 0) {
            throw new IllegalStateException("nmc.onderhoud.partitie-marge mag niet negatief zijn, is " + marge);
        }

        // Nul betekent voor PostgreSQL geen lock-timeout: dan wacht elke schrijver van event tot de statement-timeout.
        if (lockTimeout.toMillis() < 1) {
            throw new IllegalStateException("nmc.onderhoud.lock-timeout moet minstens 1 ms zijn, is " + lockTimeout);
        }

        this.repository = repository;
        this.opruimtermijnen = opruimtermijnen;
        this.omvang = omvang;
        this.marge = marge;
        this.lockTimeout = lockTimeout;
        this.epoch = epoch;
    }

    /**
     * Maakt de volgende partitie aan als de lopende voor 80% is gevuld, of als er geen partitie is die
     * de volgende transactie-id bevat.
     *
     * @return de nieuwe partitie, of leeg als er nog genoeg ruimte is
     */
    public Optional<EventPartitie> maakVolgendeAan() {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<EventPartitie> partities = repository.bereikpartities();
            long volgendeTransactieId = repository.volgendeTransactieId();
            Optional<EventPartitie> laatste = partities.isEmpty() ? Optional.empty() : Optional.of(partities.getLast());
            Optional<EventPartitie> volgende = EventPartitie.volgende(laatste, volgendeTransactieId, omvang, marge);

            if (laatste.map(EventPartitie::tot).orElse(0L) <= volgendeTransactieId) {
                // Het onderhoud liep achter: tot de nieuwe partitie er is, landen events in de default-partitie.
                Log.errorf("Onderhoud: geen eventpartitie voor transactie-id %d (laatste bereik tot %d); "
                        + "events landen in de default-partitie", volgendeTransactieId,
                        laatste.map(EventPartitie::tot).orElse(0L));
            }

            if (volgende.isPresent()) {
                repository.zetLockTimeout(lockTimeout);
                repository.zetTransactieTimeouts(AANHANG_TIMEOUT);
                repository.maak(volgende.get());
            }

            return volgende;
        });
    }

    /**
     * Verwijdert de oudste partities die weg mogen, elk in een eigen transactie, en stopt bij de eerste
     * die moet blijven.
     *
     * @return de verwijderde partities
     */
    public List<EventPartitie> ruimOp(OffsetDateTime nu) {
        List<EventPartitie> partities = QuarkusTransaction.requiringNew().call(repository::bereikpartities);
        List<EventPartitie> verwijderd = new ArrayList<>();

        for (EventPartitie partitie : partities) {
            // Eerst zonder vergrendeling, zodat een partitie die nog moet blijven de feed niet ophoudt.
            if (!QuarkusTransaction.requiringNew().call(() -> magWeg(partitie, nu)) || !verwijderOnderVergrendeling(partitie, nu)) {
                break;
            }

            verwijderd.add(partitie);
        }

        return verwijderd;
    }

    // Onder de vergrendeling opnieuw getoetst, zodat een bevestiging die tussen de eerste toets en de
    // vergrendeling is gecommit meetelt. Een cursor die pas daarna wordt bevestigd, kan in de verwijderde
    // partitie wijzen en krijgt bij de volgende aanroep 410; dat is de gedocumenteerde herstart.
    private boolean verwijderOnderVergrendeling(EventPartitie partitie, OffsetDateTime nu) {
        return QuarkusTransaction.requiringNew().call(() -> {
            repository.zetLockTimeout(lockTimeout);
            repository.vergrendelEventlog();

            if (!magWeg(partitie, nu)) {
                return false;
            }

            repository.verwijder(partitie);

            return true;
        });
    }

    /**
     * Verwijdert tot {@code maximum} events uit de default-partitie die buiten de bewaartermijn vallen en
     * waar geen geldige cursor meer naar wijst.
     *
     * @return het aantal verwijderde events
     */
    public int ruimStandaardpartitieOp(OffsetDateTime nu, int maximum) {
        return QuarkusTransaction.requiringNew().call(() -> {
            // Onder de oudste bereikpartitie die er nog staat, zodat rijen daarboven niet verdwijnen
            // terwijl een oudere partitie blijft: een verlopen cursor zou dan over het gat heen lezen.
            long onderTransactieId = Math.min(repository.watermerk(),
                    repository.bereikpartities().stream().mapToLong(EventPartitie::van).min().orElse(Long.MAX_VALUE));
            Optional<Long> beschermd = repository.oudsteBeschermdeTransactieId(epoch, nu, opruimtermijnen.maxCursorleeftijd());

            return repository.verwijderUitStandaardpartitie(opruimtermijnen.bewaargrens(nu),
                    beschermd.map(transactieId -> Math.min(transactieId, onderTransactieId)).orElse(onderTransactieId), maximum);
        });
    }

    private boolean magWeg(EventPartitie partitie, OffsetDateTime nu) {
        if (partitie.tot() > repository.watermerk()) {
            return false;
        }

        Optional<Long> beschermd = repository.oudsteBeschermdeTransactieId(epoch, nu, opruimtermijnen.maxCursorleeftijd());

        if (beschermd.isPresent() && beschermd.get() < partitie.tot()) {
            return false;
        }

        // De default-partitie wordt per rij van onderaf opgeruimd; wat daar nog onder deze partitie staat,
        // zou na het verwijderen boven het oudste event liggen en een vervallen cursor geen 410 meer geven.
        if (repository.standaardpartitieHeeftEventsOnder(partitie.tot())) {
            return false;
        }

        return repository.registratieVanJongsteEvent(partitie)
                .map(tijdstip -> tijdstip.isBefore(opruimtermijnen.bewaargrens(nu)))
                .orElse(true);
    }
}
