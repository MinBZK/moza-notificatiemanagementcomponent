package nl.rijksoverheid.moz.nmc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ConfigDvProviderTest {

    @Test
    void huidigeDvId_leestDeGeconfigureerdeUuid() {
        UUID id = UUID.randomUUID();

        assertEquals(id, new ConfigDvProvider(" " + id + " ").huidigeDvId());
    }

    @Test
    void constructor_geenUuid_weigertBijHetOpstarten() {
        assertThrows(IllegalStateException.class, () -> new ConfigDvProvider("dienstverlener-1"));
    }
}
