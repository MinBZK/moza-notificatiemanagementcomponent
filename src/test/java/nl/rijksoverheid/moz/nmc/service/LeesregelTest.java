package nl.rijksoverheid.moz.nmc.service;

import nl.rijksoverheid.moz.nmc.repository.LogboekLezer.LogboekRij;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeesregelTest {

    private static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");

    @Test
    void pasToe_logregelOpUnset_isGeslaagdZonderFoutcategorie() {
        List<Leesregel.Verwerking> verwerkingen = Leesregel.pasToe(List.of(logregel("t1", "s1", "UNSET", T0, null)));

        assertEquals(1, verwerkingen.size());
        assertTrue(verwerkingen.getFirst().geslaagd());
        assertNull(verwerkingen.getFirst().foutcategorie());
        assertEquals("notificatieAannemen", verwerkingen.getFirst().handeling());
    }

    @Test
    void pasToe_logregelOpError_isMisluktMetExceptietypeEnNietDeMelding() {
        List<Leesregel.Verwerking> verwerkingen = Leesregel.pasToe(List.of(
                logregel("t1", "s1", "ERROR", T0, "java.lang.IllegalStateException")));

        assertFalse(verwerkingen.getFirst().geslaagd());
        // De melding van de logregel zelf kan gegevens uit het verzoek bevatten; alleen het type telt.
        assertEquals("java.lang.IllegalStateException", verwerkingen.getFirst().foutcategorie());
    }

    @Test
    void pasToe_logregelMetMisluktChild_isMisluktEnHetChildIsGeenEigenVerwerking() {
        List<Leesregel.Verwerking> verwerkingen = Leesregel.pasToe(List.of(
                logregel("t1", "s1", "UNSET", T0, null),
                uitkomstChild("t1", "s2", "s1", T0.plusSeconds(1), "NetwerkFout")));

        assertEquals(1, verwerkingen.size());
        assertFalse(verwerkingen.getFirst().geslaagd());
        assertEquals("NetwerkFout", verwerkingen.getFirst().foutcategorie());
    }

    @Test
    void pasToe_losseUitkomstZonderLogregel_blijftZichtbaarAlsMislukt() {
        List<Leesregel.Verwerking> verwerkingen = Leesregel.pasToe(List.of(
                uitkomstChild("t9", "s2", "s1", T0, "NetwerkFout")));

        assertEquals(1, verwerkingen.size());
        assertFalse(verwerkingen.getFirst().geslaagd());
    }

    @Test
    void pasToe_meerdereLogregels_nieuwsteEerst() {
        List<Leesregel.Verwerking> verwerkingen = Leesregel.pasToe(List.of(
                logregel("t1", "s1", "UNSET", T0, null),
                logregel("t2", "s1", "UNSET", T0.plusSeconds(60), null)));

        assertEquals(T0.plusSeconds(60), verwerkingen.get(0).tijdstip());
        assertEquals(T0, verwerkingen.get(1).tijdstip());
    }

    private static LogboekRij logregel(String trace, String span, String status, Instant start, String foutType) {
        return new LogboekRij(trace, span, null, status, "notificatieAannemen", start, "activiteit", null, foutType,
                "melding met gegevens uit het verzoek");
    }

    private static LogboekRij uitkomstChild(String trace, String span, String parent, Instant start, String melding) {
        return new LogboekRij(trace, span, parent, "ERROR", "notificatieAannemen", start, "activiteit", "mislukt",
                "java.io.IOException", melding);
    }
}
