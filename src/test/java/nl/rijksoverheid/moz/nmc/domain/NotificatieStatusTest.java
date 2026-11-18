package nl.rijksoverheid.moz.nmc.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificatieStatusTest {

    // Legt de volledige set vast, zodat een nieuwe status een bewuste keuze vraagt.
    private static final Set<NotificatieStatus> EINDSTATUSSEN = EnumSet.of(
            NotificatieStatus.DEFINITIEF_BEZORGD, NotificatieStatus.NIET_BEZORGBAAR, NotificatieStatus.TECHNISCH_MISLUKT,
            NotificatieStatus.BEZORGSTATUS_ONBEKEND, NotificatieStatus.VERLOPEN, NotificatieStatus.GEANNULEERD);

    @ParameterizedTest
    @EnumSource(NotificatieStatus.class)
    void isEindstatus_elkeStatus_volgtDeVastgelegdeSet(NotificatieStatus status) {
        assertEquals(EINDSTATUSSEN.contains(status), status.isEindstatus());
    }
}
