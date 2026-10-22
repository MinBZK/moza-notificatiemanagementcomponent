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

    /** De handler heeft de rij zelf al afgerond in zijn eigen transactie; de worker doet niets meer. */
    record AlAfgerond() implements TaakUitkomst {
    }

    static TaakUitkomst afgerond() {
        return new Afgerond();
    }

    static TaakUitkomst uitgesteld(OffsetDateTime due, boolean teltAlsPoging) {
        return new Uitgesteld(due, teltAlsPoging);
    }

    static TaakUitkomst alAfgerond() {
        return new AlAfgerond();
    }
}
