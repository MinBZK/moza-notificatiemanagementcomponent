package nl.rijksoverheid.moz.nmc.service.taak;

import java.time.OffsetDateTime;
import java.util.Objects;

/** Wat een {@link TaakHandler} met de geclaimde taak heeft gedaan, zodat de worker de rij kan bijwerken. */
public sealed interface TaakUitkomst {

    /** De taak is klaar; de worker verwijdert de rij. */
    record Afgerond() implements TaakUitkomst {}

    /**
     * De taak komt later opnieuw aan de beurt.
     *
     * @param due           wanneer de taak opnieuw aan de beurt is
     * @param teltAlsPoging of dit uitstel meetelt voor het maximum aantal pogingen. {@code true} als
     *                      herhalen waarschijnlijk niet helpt omdat de oorzaak bij het NMC ligt,
     *                      bijvoorbeeld een verzoek dat NotifyNL als ongeldig afwijst. {@code false} als
     *                      een externe dienst niet bereikbaar is of de aanroep tijdelijk weigert: de taak
     *                      blijft het dan proberen zonder pogingen te verbruiken
     */
    record Uitgesteld(OffsetDateTime due, boolean teltAlsPoging) implements TaakUitkomst {
        public Uitgesteld {
            Objects.requireNonNull(due, "due is verplicht");
        }
    }

    /**
     * Een taak die zichzelf steeds opnieuw plant, had een geslaagde ronde: hij komt op {@code due} terug
     * en zijn pogingen gaan terug naar nul, zodat alleen fouten op rij hem op mislukt zetten.
     */
    record Herpland(OffsetDateTime due) implements TaakUitkomst {
        public Herpland {
            Objects.requireNonNull(due, "due is verplicht");
        }
    }

    /** De handler heeft de rij zelf al afgerond of uitgesteld in zijn eigen transactie; de worker doet niets meer. */
    record AlAfgerond() implements TaakUitkomst {}

    static TaakUitkomst afgerond() {
        return new Afgerond();
    }

    static TaakUitkomst uitgesteld(OffsetDateTime due, boolean teltAlsPoging) {
        return new Uitgesteld(due, teltAlsPoging);
    }

    static TaakUitkomst herpland(OffsetDateTime due) {
        return new Herpland(due);
    }

    static TaakUitkomst alAfgerond() {
        return new AlAfgerond();
    }
}
