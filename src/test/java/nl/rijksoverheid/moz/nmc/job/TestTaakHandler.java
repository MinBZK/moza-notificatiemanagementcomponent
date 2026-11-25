package nl.rijksoverheid.moz.nmc.job;

import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.service.taak.TaakHandler;
import nl.rijksoverheid.moz.nmc.service.taak.TaakUitkomst;

import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Handler waarvan een test het gedrag instelt. Geen bean: de test geeft hem via de constructor aan
 * zijn eigen {@link TaakWorker}, zodat hij niet botst met de handler die de applicatie voor dezelfde
 * taaksoort heeft of krijgt.
 */
class TestTaakHandler implements TaakHandler {

    private BiFunction<Taak, Lease, TaakUitkomst> gedrag = (taak, lease) -> TaakUitkomst.afgerond();
    private Predicate<Taak> bijUitputting = taak -> false;
    private boolean periodiek;

    void zetGedrag(BiFunction<Taak, Lease, TaakUitkomst> gedrag) {
        this.gedrag = gedrag;
    }

    void zetBijUitputting(Predicate<Taak> bijUitputting) {
        this.bijUitputting = bijUitputting;
    }

    void zetPeriodiek(boolean periodiek) {
        this.periodiek = periodiek;
    }

    @Override
    public boolean periodiek() {
        return periodiek;
    }

    @Override
    public boolean handelUitputtingAf(Taak taak) {
        return bijUitputting.test(taak);
    }

    @Override
    public TaakSoort taakSoort() {
        return TaakSoort.ONGELDIG_MELDEN;
    }

    @Override
    public TaakUitkomst voerUit(Taak taak, Lease lease) {
        return gedrag.apply(taak, lease);
    }
}
