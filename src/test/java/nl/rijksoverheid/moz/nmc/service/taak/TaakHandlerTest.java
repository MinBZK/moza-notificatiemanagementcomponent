package nl.rijksoverheid.moz.nmc.service.taak;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

// De standaarden van een handler die uitputting en herplanning niet zelf regelt.
@Tag("unit")
class TaakHandlerTest {

    private final TaakHandler handler = new TaakHandler() {
        @Override
        public TaakSoort taakSoort() {
            return TaakSoort.RECEIPT_VERWERKEN;
        }

        @Override
        public TaakUitkomst voerUit(Taak taak, Lease lease) {
            return TaakUitkomst.afgerond();
        }
    };

    // False laat de worker de uitgeputte taak op mislukt zetten, waar hij op beheer wacht.
    @Test
    void handelUitputtingAf_standaard_laatDeTaakOpMisluktGaan() {
        Taak taak = new Taak(TaakSoort.RECEIPT_VERWERKEN, null, null, OffsetDateTime.now(ZoneOffset.UTC), null, null);

        assertFalse(handler.handelUitputtingAf(taak));
    }

    @Test
    void periodiek_standaard_isNietPeriodiek() {
        assertFalse(handler.periodiek());
    }
}
