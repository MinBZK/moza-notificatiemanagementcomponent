package nl.rijksoverheid.moz.nmc.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NavraagschemaTest {

    private static final OffsetDateTime VERZONDEN = OffsetDateTime.parse("2030-01-01T10:00:00Z");

    private final Navraagschema schema = new Navraagschema(
            List.of(Duration.ofHours(6), Duration.ofHours(1), Duration.ofHours(24)), Duration.ofDays(7));

    @Test
    void eerste_isHetVroegsteMoment() {
        assertEquals(VERZONDEN.plusHours(1), schema.eerste(VERZONDEN));
    }

    @Test
    void volgende_looptLangsDeMomentenEnDaarnaDagelijks() {
        assertEquals(Optional.of(VERZONDEN.plusHours(1)), schema.volgende(VERZONDEN, VERZONDEN.plusMinutes(30)));
        assertEquals(Optional.of(VERZONDEN.plusHours(6)), schema.volgende(VERZONDEN, VERZONDEN.plusHours(1)));
        assertEquals(Optional.of(VERZONDEN.plusHours(24)), schema.volgende(VERZONDEN, VERZONDEN.plusHours(7)));
        assertEquals(Optional.of(VERZONDEN.plusDays(2)), schema.volgende(VERZONDEN, VERZONDEN.plusHours(24)));
        assertEquals(Optional.of(VERZONDEN.plusDays(5)), schema.volgende(VERZONDEN, VERZONDEN.plusDays(4).plusHours(3)));
    }

    @Test
    void volgende_eindigtOpDeBewaartermijn() {
        Navraagschema kort = new Navraagschema(List.of(Duration.ofHours(1), Duration.ofHours(30)), Duration.ofDays(1));

        assertEquals(Optional.of(VERZONDEN.plusDays(1)), kort.volgende(VERZONDEN, VERZONDEN.plusHours(2)));
        assertEquals(Optional.of(VERZONDEN.plusDays(7)), schema.volgende(VERZONDEN, VERZONDEN.plusDays(6).plusHours(1)));
        assertEquals(Optional.empty(), schema.volgende(VERZONDEN, VERZONDEN.plusDays(7)));
        assertEquals(Optional.empty(), schema.volgende(VERZONDEN, VERZONDEN.plusDays(30)));
    }

    @Test
    void eerste_voorbijDeBewaartermijn_wordtBegrensd() {
        Navraagschema kort = new Navraagschema(List.of(Duration.ofDays(2)), Duration.ofDays(1));

        assertEquals(VERZONDEN.plusDays(1), kort.eerste(VERZONDEN));
    }

    @Test
    void constructor_zonderOfMetOngeldigeMomenten_weigert() {
        assertThrows(IllegalStateException.class, () -> new Navraagschema(List.of(), Duration.ofDays(7)));
        assertThrows(IllegalStateException.class, () -> new Navraagschema(List.of(Duration.ZERO), Duration.ofDays(7)));
        assertThrows(IllegalStateException.class, () -> new Navraagschema(List.of(Duration.ofHours(-1)), Duration.ofDays(7)));
    }
}
