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
 * De toegestane statusovergangen van een notificatie. De tabel {@code toegestane_overgang} in de
 * database bevat dezelfde paren; een test houdt beide gelijk.
 * <p>
 * {@code VERZONDEN} naar {@code VERZONDEN} is de herverzending: de status blijft, er komt een nieuwe
 * poging bij. Terminale statussen hebben geen uitgaande overgang, behalve
 * {@code BEZORGSTATUS_ONBEKEND}.
 */
public final class Overgangsregels {

    private static final Map<NotificatieStatus, Set<NotificatieStatus>> TOEGESTAAN = new EnumMap<>(NotificatieStatus.class);

    static {
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

    /** Alle toegestane paren, per status van herkomst. */
    public static Map<NotificatieStatus, Set<NotificatieStatus>> alle() {
        Map<NotificatieStatus, Set<NotificatieStatus>> kopie = new EnumMap<>(NotificatieStatus.class);
        TOEGESTAAN.forEach((van, naar) -> kopie.put(van, Set.copyOf(naar)));

        return kopie;
    }
}
