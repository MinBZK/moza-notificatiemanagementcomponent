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
    void vanNaam_herinneringFieldlab_retourneertBerichtType() {
        assertEquals(BerichtType.HERINNERING_FIELDLAB, BerichtType.vanNaam("Herinnering-Fieldlab"));
        assertEquals("d394a420-3a01-420e-89f5-85742e593691", BerichtType.HERINNERING_FIELDLAB.getTemplateId());
    }

    @Test
    void vanNaam_magazijnFieldlab_retourneertBerichtType() {
        assertEquals(BerichtType.MAGAZIJN_FIELDLAB, BerichtType.vanNaam("Magazijn-Fieldlab"));
        assertEquals("d312d2b1-1b92-48ee-8aa3-5165454ac491", BerichtType.MAGAZIJN_FIELDLAB.getTemplateId());
    }

    @Test
    void vanNaam_onbekendType_gooitOnbekendBerichtTypeException() {
        assertThrows(OnbekendBerichtTypeException.class, () -> BerichtType.vanNaam("Onbekend Type XYZ"));
    }
}
