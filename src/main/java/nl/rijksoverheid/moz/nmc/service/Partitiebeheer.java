package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
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
 * bewaartermijn van het afleverbewijs en geen geldige bevestiging of leverpositie erin of eronder wijst.
 */
@ApplicationScoped
public class Partitiebeheer {

    private final EventPartitieRepository repository;
    private final Bewaartermijnen bewaartermijnen;
    private final long omvang;
    private final Duration lockTimeout;
    private final int epoch;

    public Partitiebeheer(EventPartitieRepository repository, Bewaartermijnen bewaartermijnen,
                          @ConfigProperty(name = "nmc.onderhoud.partitie-omvang") long omvang,
                          @ConfigProperty(name = "nmc.onderhoud.lock-timeout") Duration lockTimeout,
                          @ConfigProperty(name = "nmc.feed.cluster-epoch") int epoch) {
        if (omvang < 1) {
            throw new IllegalStateException("nmc.onderhoud.partitie-omvang moet 1 of hoger zijn, is " + omvang);
        }

        this.repository = repository;
        this.bewaartermijnen = bewaartermijnen;
        this.omvang = omvang;
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
            Optional<EventPartitie> volgende = volgende(partities.isEmpty() ? Optional.empty()
                    : Optional.of(partities.getLast()), repository.volgendeXid(), omvang);

            if (volgende.isPresent()) {
                repository.zetLockTimeout(lockTimeout);
                repository.maak(volgende.get());
            }

            return volgende;
        });
    }

    /**
     * De partitie die na {@code laatste} moet komen. Is de laatste nog niet voor 80% gevuld, dan geen.
     * Ligt de volgende transactie-id er al boven, dan begint de nieuwe daar: de default-partitie kan
     * rijen tussen de laatste en die transactie-id hebben.
     */
    static Optional<EventPartitie> volgende(Optional<EventPartitie> laatste, long volgendeXid, long omvang) {
        if (laatste.isEmpty() || volgendeXid >= laatste.get().tot()) {
            return Optional.of(EventPartitie.vanaf(volgendeXid, volgendeXid + omvang));
        }

        EventPartitie lopend = laatste.get();

        if ((volgendeXid - lopend.van()) * 5 >= (lopend.tot() - lopend.van()) * 4) {
            return Optional.of(EventPartitie.vanaf(lopend.tot(), lopend.tot() + omvang));
        }

        return Optional.empty();
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
            if (!QuarkusTransaction.requiringNew().call(() -> magWeg(partitie, nu)) || !verwijderVergrendeld(partitie, nu)) {
                break;
            }

            verwijderd.add(partitie);
        }

        return verwijderd;
    }

    // Onder de vergrendeling opnieuw getoetst: een bevestiging die nog tussen de eerste toets en de
    // vergrendeling committe, telt zo mee.
    private boolean verwijderVergrendeld(EventPartitie partitie, OffsetDateTime nu) {
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
            long onder = repository.watermerk();
            Optional<Long> beschermd = repository.oudsteBeschermdeXid(epoch, nu, bewaartermijnen.maxCursorleeftijd());

            return repository.verwijderUitStandaardpartitie(bewaartermijnen.bewarenVanaf(nu),
                    beschermd.map(xid -> Math.min(xid, onder)).orElse(onder), maximum);
        });
    }

    private boolean magWeg(EventPartitie partitie, OffsetDateTime nu) {
        if (partitie.tot() > repository.watermerk()) {
            return false;
        }

        Optional<Long> beschermd = repository.oudsteBeschermdeXid(epoch, nu, bewaartermijnen.maxCursorleeftijd());

        if (beschermd.isPresent() && beschermd.get() < partitie.tot()) {
            return false;
        }

        return repository.jongsteEvent(partitie)
                .map(tijdstip -> tijdstip.isBefore(bewaartermijnen.bewarenVanaf(nu)))
                .orElse(true);
    }
}
