package nl.rijksoverheid.moz.nmc.service;

import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;

/**
 * Voert taken van één soort uit. Per soort is er ten hoogste één handler; de worker vindt hem op
 * {@link #soort()}.
 * <p>
 * {@link #voerUit} draait buiten een transactie. Een handler die werk, taakafronding en event samen
 * wil committen opent zelf een transactie, rondt daarin de taak af via {@link TaakClaimer#rondAf} en
 * geeft {@link TaakUitkomst#alAfgerond()} terug. Vóór elke externe aanroep verlengt hij de lease.
 */
public interface TaakHandler {

    TaakSoort soort();

    /**
     * @param lease verlengt de lease op de taak; aan te roepen vóór elke externe aanroep
     * @throws TaakVerlorenException als de lease inmiddels bij een andere worker ligt
     */
    TaakUitkomst voerUit(Taak taak, Lease lease);

    /** De lease die een worker op een geclaimde taak houdt. */
    @FunctionalInterface
    interface Lease {
        void verleng();
    }
}
