package nl.rijksoverheid.moz.nmc.job;

import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.service.TaakHandler;
import nl.rijksoverheid.moz.nmc.service.TaakUitkomst;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * Handler voor de soort CONTROLE in de testsuite; er is nog geen echte. Het gedrag is per test in te
 * stellen. De worker-lus staat in tests uit, dus deze handler draait alleen als een test een ronde
 * aanroept.
 */
@ApplicationScoped
public class ControleTestHandler implements TaakHandler {

    static final AtomicReference<BiFunction<Taak, Lease, TaakUitkomst>> GEDRAG =
            new AtomicReference<>((taak, lease) -> TaakUitkomst.afgerond());

    static final AtomicReference<java.util.function.Predicate<Taak>> BIJ_UITPUTTING = new AtomicReference<>(taak -> false);

    static final AtomicBoolean PERIODIEK = new AtomicBoolean();

    @Override
    public boolean periodiek() {
        return PERIODIEK.get();
    }

    @Override
    public boolean uitgeput(Taak taak) {
        return BIJ_UITPUTTING.get().test(taak);
    }

    @Override
    public TaakSoort soort() {
        return TaakSoort.CONTROLE;
    }

    @Override
    public TaakUitkomst voerUit(Taak taak, Lease lease) {
        return GEDRAG.get().apply(taak, lease);
    }
}
