package nl.rijksoverheid.moz.nmc.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusWaardeTest {

    // toApiValue() is de @JsonValue-serialisatie in de CloudEvent naar de Dienstverlener: een
    // regressie hier breekt consumenten stil, zonder dat enige andere test dit zou opmerken.
    @ParameterizedTest
    @CsvSource({
            "SENDING, sending",
            "DELIVERED, delivered",
            "PERMANENT_FAILURE, permanent-failure",
            "TEMPORARY_FAILURE, temporary-failure",
            "TECHNICAL_FAILURE, technical-failure",
            "CREATED, created",
            "ONBEKEND, onbekend"
    })
    void toApiValue_retourneertKebabCase(StatusWaarde status, String verwacht) {
        assertEquals(verwacht, status.toApiValue());
    }

    @ParameterizedTest
    @EnumSource(value = StatusWaarde.class,
            names = {"DELIVERED", "PERMANENT_FAILURE", "TEMPORARY_FAILURE", "TECHNICAL_FAILURE"})
    void isDefinitief_eindstatussenVanNotifyNl_retourneertTrue(StatusWaarde status) {
        assertTrue(status.isDefinitief());
    }

    @ParameterizedTest
    @EnumSource(value = StatusWaarde.class, names = {"CREATED", "SENDING", "ONBEKEND"})
    void isDefinitief_statussenWaarNietZekerIsDatErGeenVervolgcallbackKomt_retourneertFalse(StatusWaarde status) {
        assertFalse(status.isDefinitief());
    }
}
