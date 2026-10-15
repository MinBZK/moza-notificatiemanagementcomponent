package nl.rijksoverheid.moz.nmc.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static nl.rijksoverheid.moz.nmc.domain.NotificatieStatus.AANGENOMEN;
import static nl.rijksoverheid.moz.nmc.domain.NotificatieStatus.BEZORGD;
import static nl.rijksoverheid.moz.nmc.domain.NotificatieStatus.BEZORGSTATUS_ONBEKEND;
import static nl.rijksoverheid.moz.nmc.domain.NotificatieStatus.DEFINITIEF_BEZORGD;
import static nl.rijksoverheid.moz.nmc.domain.NotificatieStatus.GEANNULEERD;
import static nl.rijksoverheid.moz.nmc.domain.NotificatieStatus.IN_VERZENDING;
import static nl.rijksoverheid.moz.nmc.domain.NotificatieStatus.NIET_BEZORGBAAR;
import static nl.rijksoverheid.moz.nmc.domain.NotificatieStatus.TECHNISCH_MISLUKT;
import static nl.rijksoverheid.moz.nmc.domain.NotificatieStatus.VERLOPEN;
import static nl.rijksoverheid.moz.nmc.domain.NotificatieStatus.VERZONDEN;

/**
 * De toegestane statusovergangen van een notificatie.
 * <p>
 * {@code VERZONDEN} naar {@code VERZONDEN} is de herverzending: de status blijft, er komt een nieuwe
 * poging bij. Eindstatussen hebben geen uitgaande overgang, behalve
 * {@code BEZORGSTATUS_ONBEKEND}. Ook welke reden bij een status hoort, staat hier.
 */
public final class Overgangsregels {

    private static final Map<NotificatieStatus, Set<NotificatieStatus>> TOEGESTAAN = new EnumMap<>(NotificatieStatus.class);

    // De reden is wat de Dienstverlener te zien krijgt; alleen deze eindstatussen dragen er een.
    private static final Map<NotificatieStatus, Set<Reden>> REDENEN = new EnumMap<>(NotificatieStatus.class);

    static {
        REDENEN.put(NIET_BEZORGBAAR, EnumSet.of(Reden.ONBEREIKBAAR, Reden.GEEN_CONTACTGEGEVENS));
        REDENEN.put(TECHNISCH_MISLUKT, EnumSet.of(Reden.TECHNISCH));
        REDENEN.put(VERLOPEN, EnumSet.of(Reden.VERLOPEN));
        REDENEN.put(GEANNULEERD, EnumSet.of(Reden.GEANNULEERD));
        TOEGESTAAN.put(AANGENOMEN, EnumSet.of(IN_VERZENDING, VERLOPEN, GEANNULEERD));
        TOEGESTAAN.put(IN_VERZENDING, EnumSet.of(VERZONDEN, TECHNISCH_MISLUKT, VERLOPEN, NIET_BEZORGBAAR));
        TOEGESTAAN.put(VERZONDEN, EnumSet.of(VERZONDEN, VERLOPEN, BEZORGD, NIET_BEZORGBAAR, TECHNISCH_MISLUKT,
                BEZORGSTATUS_ONBEKEND));
        TOEGESTAAN.put(BEZORGSTATUS_ONBEKEND, EnumSet.of(BEZORGD, NIET_BEZORGBAAR));
        TOEGESTAAN.put(BEZORGD, EnumSet.of(DEFINITIEF_BEZORGD, VERZONDEN, NIET_BEZORGBAAR, VERLOPEN));
    }

    private Overgangsregels() {
    }

    public static boolean isToegestaan(NotificatieStatus van, NotificatieStatus naar) {
        return TOEGESTAAN.getOrDefault(van, Set.of()).contains(naar);
    }

    /** Of de reden bij de status hoort: een passende reden bij een eindstatus met reden, geen reden bij de andere. */
    public static boolean isRedenToegestaan(NotificatieStatus naar, Reden reden) {
        Set<Reden> redenen = REDENEN.getOrDefault(naar, Set.of());

        return redenen.isEmpty() ? reden == null : reden != null && redenen.contains(reden);
    }

    /** Alle toegestane paren, per status van herkomst. */
    public static Map<NotificatieStatus, Set<NotificatieStatus>> alle() {
        Map<NotificatieStatus, Set<NotificatieStatus>> kopie = new EnumMap<>(NotificatieStatus.class);
        TOEGESTAAN.forEach((van, naar) -> kopie.put(van, Set.copyOf(naar)));

        return kopie;
    }
}
