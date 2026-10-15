package nl.rijksoverheid.moz.nmc.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebhookUrlControleTest {

    private final WebhookUrlControle controle = new WebhookUrlControle();

    @Test
    void controleer_geldigeUrl_geeftHemGenormaliseerdTerug() {
        assertEquals("https://dv.example.nl/webhook", controle.controleer("HTTPS://dv.example.nl/webhook"));
    }

    @Test
    void controleer_interneOfOnveiligeUrl_weigert() {
        assertThrows(OngeldigeCallbackUrlException.class, () -> controle.controleer("http://dv.example.nl/webhook"));
        assertThrows(OngeldigeCallbackUrlException.class, () -> controle.controleer("https://10.0.0.1/webhook"));
    }

    @Test
    void controleer_nietTeOntleden_weigert() {
        assertThrows(OngeldigeCallbackUrlException.class, () -> controle.controleer("https://dv example.nl/webhook"));
    }
}
