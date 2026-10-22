package nl.rijksoverheid.moz.nmc.job;

import nl.rijksoverheid.moz.nmc.domain.TaakSoort;

/**
 * Draait vanuit een test buiten dit package één ronde van de worker voor een taaksoort, zonder
 * {@link TaakWorker#verwerk(TaakSoort)} publiek te maken.
 */
public final class TaakRonde {

    private TaakRonde() {
    }

    /** @return het aantal geclaimde taken */
    public static int draai(TaakWorker taakWorker, TaakSoort taakSoort) {
        return taakWorker.verwerk(taakSoort);
    }
}
