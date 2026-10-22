package nl.rijksoverheid.moz.nmc.service;

import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;

/**
 * Voert taken van één soort uit. Per soort is er ten hoogste één handler; de worker vindt hem op
 * {@link #taakSoort()}.
 * <p>
 * {@link #voerUit} draait buiten een transactie en kan na een geslaagde uitvoering opnieuw draaien: de
 * worker rondt de taak daarna in een eigen transactie af, en een verloren lease, een gestopte pod of
 * een databasefout daartussen laat de taak terugkomen. Een handler is daarom idempotent, of hij opent
 * zelf een transactie, rondt daarin de taak af via {@link TaakClaimer#rondAf} en geeft
 * {@link TaakUitkomst#alAfgerond()} terug, zodat werk, taakafronding en event samen committen. Vóór
 * elke externe aanroep verlengt hij de lease.
 */
public interface TaakHandler {

    TaakSoort taakSoort();

    /**
     * @param lease verlengt de lease op de taak; aan te roepen vóór elke externe aanroep
     * @throws TaakVerlorenException als de lease inmiddels bij een andere worker ligt
     */
    TaakUitkomst voerUit(Taak taak, Lease lease);

    /**
     * De taak heeft het maximum aantal pogingen bereikt. Draait in de transactie waarin de worker de
     * taak daarna afrondt of op mislukt zet, zodat een overgang van de notificatie en de taakrij samen
     * committen.
     *
     * @return true als de handler de uitputting heeft afgehandeld en de taak af is; false laat de taak
     *         op mislukt gaan, waar hij op beheer wacht
     */
    default boolean uitgeput(Taak taak) {
        return false;
    }

    /**
     * Een periodieke taak heeft één rij per systeem en plant zichzelf opnieuw. Na het maximum aantal
     * pogingen gaat hij niet op mislukt, want niets maakt hem opnieuw aan: de worker plant hem met de
     * langste wachttijd opnieuw in.
     */
    default boolean periodiek() {
        return false;
    }

    /** De lease die een worker op een geclaimde taak houdt. */
    @FunctionalInterface
    interface Lease {
        void verleng();
    }
}
