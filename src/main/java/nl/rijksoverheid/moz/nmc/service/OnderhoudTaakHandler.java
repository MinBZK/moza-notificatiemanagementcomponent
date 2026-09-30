package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.EventPartitie;
import nl.rijksoverheid.moz.nmc.repository.GewrapteSleutel;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Het onderhoud per systeem: maakt de volgende partitie van het eventlog aan, herwrapt sleutels onder
 * een oudere KEK-versie, verwijdert notificaties (met hun pogingen) na de bewaartermijn van het
 * afleverbewijs en ruimt het eventlog op. Alles in kleine batches met een eigen, korte transactie,
 * omdat elke lopende schrijftransactie het watermerk van de feed vasthoudt.
 * <p>
 * De taak plant zichzelf na elke ronde opnieuw; migratie V17 zet de eerste rij. Een stap die faalt
 * wordt gelogd en de volgende stappen lopen door, zodat bijvoorbeeld een bezet eventlog het wissen van
 * rijen niet ophoudt.
 */
@ApplicationScoped
public class OnderhoudTaakHandler implements TaakHandler {

    private static final UUID LAAGSTE_ID = new UUID(0, 0);

    private final Partitiebeheer partitiebeheer;
    private final NotificatieRepository notificatieRepository;
    private final Sleutelbeheer sleutelbeheer;
    private final Bewaartermijnen bewaartermijnen;
    private final Duration interval;
    private final int batch;
    private final int maxBatches;

    public OnderhoudTaakHandler(Partitiebeheer partitiebeheer, NotificatieRepository notificatieRepository,
                                Sleutelbeheer sleutelbeheer, Bewaartermijnen bewaartermijnen,
                                @ConfigProperty(name = "nmc.onderhoud.interval") Duration interval,
                                @ConfigProperty(name = "nmc.onderhoud.batch") int batch,
                                @ConfigProperty(name = "nmc.onderhoud.max-batches") int maxBatches) {
        if (batch < 1 || maxBatches < 1) {
            throw new IllegalStateException("nmc.onderhoud.batch en nmc.onderhoud.max-batches moeten 1 of hoger zijn");
        }

        this.partitiebeheer = partitiebeheer;
        this.notificatieRepository = notificatieRepository;
        this.sleutelbeheer = sleutelbeheer;
        this.bewaartermijnen = bewaartermijnen;
        this.interval = interval;
        this.batch = batch;
        this.maxBatches = maxBatches;
    }

    @Override
    public boolean periodiek() {
        return true;
    }

    @Override
    public TaakSoort soort() {
        return TaakSoort.ONDERHOUD;
    }

    @Override
    public TaakUitkomst voerUit(Taak taak, Lease lease) {
        OffsetDateTime nu = OffsetDateTime.now(ZoneOffset.UTC);

        stap("partitie aanmaken", () -> partitiebeheer.maakVolgendeAan()
                .ifPresent(p -> Log.infof("Onderhoud: eventpartitie %s aangemaakt (%d tot %d)", p.naam(), p.van(), p.tot())));
        lease.verleng();
        stap("sleutels herwrappen", () -> meld("sleutel(s) geherwrapt", herwrapSleutels(lease)));
        stap("notificaties verwijderen", () -> meld("notificatie(s) na de bewaartermijn verwijderd",
                inBatches(lease, () -> QuarkusTransaction.requiringNew()
                        .call(() -> notificatieRepository.verwijderTerminaalVoor(bewaartermijnen.bewarenVanaf(nu), batch)))));
        lease.verleng();
        stap("eventpartities verwijderen", () -> partitiebeheer.ruimOp(nu).stream().map(EventPartitie::naam)
                .forEach(naam -> Log.infof("Onderhoud: eventpartitie %s verwijderd", naam)));
        lease.verleng();
        stap("default-partitie opruimen", () -> meld("event(s) uit de default-partitie verwijderd",
                inBatches(lease, () -> partitiebeheer.ruimStandaardpartitieOp(nu, batch))));

        return TaakUitkomst.herpland(OffsetDateTime.now(ZoneOffset.UTC).plus(interval));
    }

    /**
     * Herwrapt de sleutels onder een oudere KEK-versie met de huidige, zonder de gegevens opnieuw te
     * versleutelen. Een sleutel waarvan de oude KEK ontbreekt of niet past, blijft staan en wordt gelogd.
     *
     * @return het aantal geherwrapte sleutels
     */
    private int herwrapSleutels(Lease lease) {
        int huidige = sleutelbeheer.huidigeKekVersie();
        UUID na = LAAGSTE_ID;
        int herwrapt = 0;

        for (int i = 0; i < maxBatches; i++) {
            UUID vanaf = na;
            List<GewrapteSleutel> sleutels = QuarkusTransaction.requiringNew().call(() -> {
                List<GewrapteSleutel> vergrendeld = notificatieRepository.vergrendelOudeSleutels(huidige, vanaf, batch);
                vergrendeld.forEach(sleutel -> herwrap(sleutel, huidige));

                return vergrendeld;
            });
            herwrapt += sleutels.size();

            if (sleutels.size() < batch) {
                break;
            }

            na = sleutels.getLast().notificatieId();
            lease.verleng();
        }

        return herwrapt;
    }

    private void herwrap(GewrapteSleutel sleutel, int huidige) {
        try {
            byte[] nieuw = sleutelbeheer.herwrap(sleutel.notificatieId(), sleutel.sleutel(), sleutel.kekVersie());
            notificatieRepository.zetSleutel(sleutel.notificatieId(), nieuw, huidige);
        } catch (OntsleutelenMisluktException e) {
            Log.errorf("Onderhoud: sleutel van notificatie %s onder KEK-versie %d niet te herwrappen: %s",
                    sleutel.notificatieId(), sleutel.kekVersie(), e.getMessage());
        }
    }

    // Tot een batch niet vol is of het maximum per ronde is bereikt; wat blijft staan komt de volgende ronde.
    private int inBatches(Lease lease, IntSupplier batchUitvoering) {
        int totaal = 0;

        for (int i = 0; i < maxBatches; i++) {
            int aantal = batchUitvoering.getAsInt();
            totaal += aantal;

            if (aantal < batch) {
                break;
            }

            lease.verleng();
        }

        return totaal;
    }

    private static void stap(String naam, Runnable stap) {
        try {
            stap.run();
        } catch (TaakVerlorenException e) {
            throw e;
        } catch (RuntimeException e) {
            Log.errorf(e, "Onderhoud: stap '%s' mislukt; de volgende ronde probeert het opnieuw", naam);
        }
    }

    private static void meld(String wat, int aantal) {
        if (aantal > 0) {
            Log.infof("Onderhoud: %d %s", aantal, wat);
        }
    }
}
