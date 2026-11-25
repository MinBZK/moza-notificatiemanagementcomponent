package nl.rijksoverheid.moz.nmc.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OvergangsregelsTest {

    // Legt de volledige set vast, zodat een gewijzigde regel een bewuste wijziging van deze test vraagt.
    @Test
    void alle_bevatPreciesDeToegestaneOvergangen() {
        Set<String> paren = new HashSet<>();
        Overgangsregels.alle().forEach((van, naar) -> naar.forEach(n -> paren.add(van.name() + ">" + n.name())));

        assertEquals(Set.of(
                "AANGENOMEN>IN_VERZENDING",
                "AANGENOMEN>VERLOPEN",
                "AANGENOMEN>GEANNULEERD",
                "IN_VERZENDING>VERZONDEN",
                "IN_VERZENDING>TECHNISCH_MISLUKT",
                "IN_VERZENDING>VERLOPEN",
                "IN_VERZENDING>NIET_BEZORGBAAR",
                "VERZONDEN>VERZONDEN",
                "VERZONDEN>VERLOPEN",
                "VERZONDEN>BEZORGD",
                "VERZONDEN>NIET_BEZORGBAAR",
                "VERZONDEN>TECHNISCH_MISLUKT",
                "VERZONDEN>BEZORGSTATUS_ONBEKEND",
                "BEZORGSTATUS_ONBEKEND>BEZORGD",
                "BEZORGSTATUS_ONBEKEND>NIET_BEZORGBAAR",
                "BEZORGD>DEFINITIEF_BEZORGD",
                "BEZORGD>VERZONDEN",
                "BEZORGD>NIET_BEZORGBAAR",
                "BEZORGD>VERLOPEN"), paren);
    }

    @Test
    void isToegestaan_eindstatus_heeftGeenUitgaandeOvergang() {
        for (NotificatieStatus naar : NotificatieStatus.values()) {
            assertFalse(Overgangsregels.isToegestaan(NotificatieStatus.DEFINITIEF_BEZORGD, naar));
            assertFalse(Overgangsregels.isToegestaan(NotificatieStatus.GEANNULEERD, naar));
        }
    }

    @Test
    void isToegestaan_herverzending_isToegestaan() {
        assertTrue(Overgangsregels.isToegestaan(NotificatieStatus.VERZONDEN, NotificatieStatus.VERZONDEN));
    }

    @Test
    void isRedenToegestaan_eindstatusMetReden_vraagtEenPassendeReden() {
        assertTrue(Overgangsregels.isRedenToegestaan(NotificatieStatus.NIET_BEZORGBAAR, Reden.ONBEREIKBAAR));
        assertTrue(Overgangsregels.isRedenToegestaan(NotificatieStatus.NIET_BEZORGBAAR, Reden.GEEN_CONTACTGEGEVENS));
        assertTrue(Overgangsregels.isRedenToegestaan(NotificatieStatus.TECHNISCH_MISLUKT, Reden.TECHNISCH));
        assertTrue(Overgangsregels.isRedenToegestaan(NotificatieStatus.VERLOPEN, Reden.VERLOPEN));
        assertTrue(Overgangsregels.isRedenToegestaan(NotificatieStatus.GEANNULEERD, Reden.GEANNULEERD));
        assertFalse(Overgangsregels.isRedenToegestaan(NotificatieStatus.NIET_BEZORGBAAR, null));
        assertFalse(Overgangsregels.isRedenToegestaan(NotificatieStatus.NIET_BEZORGBAAR, Reden.TECHNISCH));
    }

    @Test
    void isRedenToegestaan_statusZonderReden_verdraagtGeenReden() {
        assertTrue(Overgangsregels.isRedenToegestaan(NotificatieStatus.BEZORGD, null));
        assertTrue(Overgangsregels.isRedenToegestaan(NotificatieStatus.BEZORGSTATUS_ONBEKEND, null));
        assertFalse(Overgangsregels.isRedenToegestaan(NotificatieStatus.BEZORGD, Reden.TECHNISCH));
    }

    @Test
    void isToegestaan_leegVertrekpunt_isNietToegestaan() {
        assertFalse(Overgangsregels.isToegestaan(null, NotificatieStatus.AANGENOMEN));
    }
}
