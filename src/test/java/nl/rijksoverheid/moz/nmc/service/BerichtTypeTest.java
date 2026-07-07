package nl.rijksoverheid.moz.nmc.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BerichtTypeTest {

    @Test
    void vanNaam_test_retourneertBerichtType() {
        assertEquals(BerichtType.TEST, BerichtType.vanNaam("Test"));
        assertEquals("fd2aad70-09d9-4af3-9ac9-f0c418b1b47a", BerichtType.TEST.getTemplateId());
    }

    @Test
    void vanNaam_stuurgroepAgenda_retourneertBerichtType() {
        assertEquals(BerichtType.STUURGROEP_AGENDA, BerichtType.vanNaam("Stuurgroep Agenda"));
        assertEquals("e72c75c5-e74c-4a78-8f2d-c06187a4d51c", BerichtType.STUURGROEP_AGENDA.getTemplateId());
    }

    @Test
    void vanNaam_onbekendType_gooitOnbekendBerichtTypeException() {
        assertThrows(OnbekendBerichtTypeException.class, () -> BerichtType.vanNaam("Onbekend Type XYZ"));
    }
}
