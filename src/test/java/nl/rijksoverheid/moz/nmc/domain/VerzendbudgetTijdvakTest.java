package nl.rijksoverheid.moz.nmc.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class VerzendbudgetTijdvakTest {

    private VerzendbudgetTijdvak tijdvak;

    // Tien tokens, waarvan drie gereserveerd voor verzenden en twee voor de navraag.
    @BeforeEach
    void setUp() {
        tijdvak = new VerzendbudgetTijdvak();
        tijdvak.geefTerug(BudgetDoel.VERZENDEN, 10, 3, 10, 3);
        tijdvak.geefTerug(BudgetDoel.NAVRAAG, 0, 2, 10, 2);
    }

    @Test
    void neem_allesWatBeschikbaarIs_neemtEerstUitDeEigenReservering() {
        assertEquals(8, tijdvak.beschikbaar(BudgetDoel.VERZENDEN));

        assertEquals(3, tijdvak.neem(BudgetDoel.VERZENDEN, 8));

        assertEquals(0, tijdvak.beschikbaar(BudgetDoel.VERZENDEN));
        assertEquals(2, tijdvak.beschikbaar(BudgetDoel.NAVRAAG), "de reservering van de navraag blijft staan");
    }

    @Test
    void neem_meerDanBeschikbaar_weigertEnLaatHetBudgetStaan() {
        IllegalArgumentException fout =
                assertThrows(IllegalArgumentException.class, () -> tijdvak.neem(BudgetDoel.VERZENDEN, 9));

        assertTrue(
                fout.getMessage().contains("Kan geen 9 tokens nemen voor VERZENDEN, er zijn er 8"), fout.getMessage());
        assertEquals(8, tijdvak.beschikbaar(BudgetDoel.VERZENDEN));
        assertEquals(7, tijdvak.beschikbaar(BudgetDoel.NAVRAAG));
    }

    @Test
    void neem_negatiefAantal_weigertEnLaatHetBudgetStaan() {
        assertThrows(IllegalArgumentException.class, () -> tijdvak.neem(BudgetDoel.NAVRAAG, -1));

        assertEquals(7, tijdvak.beschikbaar(BudgetDoel.NAVRAAG));
        assertEquals(0, tijdvak.neem(BudgetDoel.NAVRAAG, 0));
        assertEquals(7, tijdvak.beschikbaar(BudgetDoel.NAVRAAG));
    }
}
