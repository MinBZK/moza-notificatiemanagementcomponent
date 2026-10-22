package nl.rijksoverheid.moz.nmc.service;

import java.time.OffsetDateTime;
import java.util.Objects;

/** Wat een {@link TaakHandler} met de geclaimde taak heeft gedaan, zodat de worker de rij kan bijwerken. */
public sealed interface TaakUitkomst {

    /** De taak is klaar; de worker verwijdert de rij. */
    record Afgerond() implements TaakUitkomst {
    }

    /**
     * De taak komt later opnieuw aan de beurt.
     *
     * @param due           wanneer
     * @param teltAlsPoging of dit uitstel een mislukking in het NMC zelf was; een fout bij een externe
     *                      dienst telt niet mee, zodat een storing daar de taak geen pogingen kost
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
    record AlAfgerond() implements TaakUitkomst {
    }

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
