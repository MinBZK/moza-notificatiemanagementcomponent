package nl.rijksoverheid.moz.nmc.job;

import java.time.Duration;

/**
 * De instellingen van de retentiejob, met hun controles op één plek.
 *
 * @param bewaartermijn hoe lang een notificatie na haar laatste statusregistratie blijft staan
 * @param maxBatches bovengrens op het aantal batches per run, tegen een onverwacht grote achterstand
 */
public record RetentieConfiguratie(Duration bewaartermijn, int maxBatches) {

    public RetentieConfiguratie {
        // Een niet-positieve termijn is één configuratie-typefout verwijderd van "verwijder de hele
        // tabel bij de volgende run", dat hoort bij het opstarten te falen, niet stilletjes midden
        // in de nacht.
        if (bewaartermijn.isNegative() || bewaartermijn.isZero()) {
            throw new IllegalArgumentException(
                    "notificatie.retentie.bewaartermijn moet positief zijn, maar was " + bewaartermijn);
        }

        // Een bovengrens van 0 zou betekenen dat de lus nooit draait en de tabel doorgroeit.
        if (maxBatches < 1) {
            throw new IllegalArgumentException(
                    "notificatie.retentie.max-batches moet minstens 1 zijn, maar was " + maxBatches);
        }
    }
}
