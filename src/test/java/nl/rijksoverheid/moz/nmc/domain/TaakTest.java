package nl.rijksoverheid.moz.nmc.domain;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaakTest {

    @Test
    void getPayload_metEnZonderPayload_geeftEenKopieOfEenLegeMap() {
        OffsetDateTime nu = OffsetDateTime.now(ZoneOffset.UTC);
        Taak metPayload = new Taak(TaakSoort.CONTROLE, null, null, nu, null, Map.of("k", "v"));
        Taak zonderPayload = new Taak(TaakSoort.CONTROLE, null, null, nu, null, null);

        assertEquals(Map.of("k", "v"), metPayload.getPayload());
        assertEquals(Map.of(), zonderPayload.getPayload());
    }
}
