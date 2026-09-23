package nl.rijksoverheid.moz.nmc.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BerichtTypeTest {

    @Test
    void vanNaam_stuurgroepAgenda_retourneertBerichtType() {
        assertEquals(BerichtType.STUURGROEP_AGENDA, BerichtType.vanNaam("Stuurgroep Agenda"));
        assertEquals("fee3eaf3-53ab-44ce-ac09-1ef7070b28a1", BerichtType.STUURGROEP_AGENDA.getTemplateId());
    }

    @Test
    void vanNaam_demoTemplate_retourneertBerichtType() {
        assertEquals(BerichtType.DEMO_TEMPLATE, BerichtType.vanNaam("Demo template"));
        assertEquals("09d9343b-0a55-43cc-887b-c36cb6c9123d", BerichtType.DEMO_TEMPLATE.getTemplateId());
    }

    @Test
    void vanNaam_onbekendType_gooitOnbekendBerichtTypeException() {
        assertThrows(OnbekendBerichtTypeException.class, () -> BerichtType.vanNaam("Onbekend Type XYZ"));
    }
}
