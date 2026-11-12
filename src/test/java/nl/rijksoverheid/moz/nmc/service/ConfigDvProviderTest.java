package nl.rijksoverheid.moz.nmc.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
