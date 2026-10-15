package nl.rijksoverheid.moz.nmc.service;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerzendbeleidTest {

    private final Verzendbeleid beleid = beleid(1, Map.of(
            "nmc.beleid.stuurgroep_agenda.geldigheid", "P1D",
            "nmc.beleid.stuurgroep_agenda.herverzend-wachttijd", "PT10M",
            "nmc.beleid.stuurgroep_agenda.max-herverzendingen", "3"));

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

    @Test
    void maxHerverzendingen_perBerichttypeOfStandaard() {
        assertEquals(3, beleid.maxHerverzendingen("STUURGROEP_AGENDA"));
        assertEquals(1, beleid.maxHerverzendingen("DEMO_TEMPLATE"));
        assertEquals(1, beleid.maxHerverzendingen(null), "rijen van vóór het beleid");
    }

    @Test
    void magHerverzenden_totEnMetDeLaatsteToegestanePoging() {
        assertTrue(beleid.magHerverzenden("DEMO_TEMPLATE", 1));
        assertFalse(beleid.magHerverzenden("DEMO_TEMPLATE", 2));
        assertTrue(beleid.magHerverzenden("STUURGROEP_AGENDA", 3));
        assertFalse(beleid.magHerverzenden("STUURGROEP_AGENDA", 4));
    }

    @Test
    void geenHerverzendingen_laatDeEerstePogingDeLaatsteZijn() {
        assertFalse(beleid(0, Map.of()).magHerverzenden("DEMO_TEMPLATE", 1));
    }

    @Test
    void negatiefAantalHerverzendingen_laatDeApplicatieNietStarten() {
        assertThrows(IllegalStateException.class, () -> beleid(-1, Map.of()));
        assertThrows(IllegalStateException.class,
                () -> beleid(1, Map.of("nmc.beleid.demo_template.max-herverzendingen", "-1")));
    }

    private static Verzendbeleid beleid(int maxHerverzendingen, Map<String, String> config) {
        return new Verzendbeleid(Duration.ofDays(7), Duration.ofHours(1), maxHerverzendingen,
                new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(config, "test", 100)).build());
    }
}
