package nl.rijksoverheid.moz.nmc.service;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VerzendbeleidTest {

    private final Verzendbeleid beleid = new Verzendbeleid(Duration.ofDays(7), Duration.ofHours(1),
            new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(Map.of(
                    "nmc.beleid.stuurgroep_agenda.geldigheid", "P1D",
                    "nmc.beleid.stuurgroep_agenda.herverzend-wachttijd", "PT10M"), "test", 100)).build());

    @Test
    void geldigheid_perBerichttypeOfStandaard() {
        assertEquals(Duration.ofDays(1), beleid.geldigheid(BerichtType.STUURGROEP_AGENDA));
        assertEquals(Duration.ofDays(7), beleid.geldigheid(BerichtType.DEMO_TEMPLATE));
        assertEquals(Duration.ofDays(7), beleid.geldigheid(null));
    }

    @Test
    void herverzendWachttijd_perBerichttypeOfStandaard() {
        assertEquals(Duration.ofMinutes(10), beleid.herverzendWachttijd("STUURGROEP_AGENDA"));
        assertEquals(Duration.ofHours(1), beleid.herverzendWachttijd("DEMO_TEMPLATE"));
        assertEquals(Duration.ofHours(1), beleid.herverzendWachttijd(null), "rijen van vóór het beleid");
    }
}
