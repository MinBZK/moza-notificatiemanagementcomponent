package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.BudgetDoel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

// %test: 20 tokens per minuut, waarvan 5 gereserveerd voor de navraag.
@QuarkusTest
class VerzendbudgetTest {

    private static final Instant TIJDVAK_A = Instant.parse("2030-01-01T10:00:30Z");
    private static final Instant TIJDVAK_B = Instant.parse("2030-01-01T10:01:00Z");

    @Inject
    Verzendbudget verzendbudget;

    @Inject
    EntityManager entityManager;

    @InjectMock
    Clock klok;

    @BeforeEach
    void setUp() {
        when(klok.instant()).thenReturn(TIJDVAK_A);
        when(klok.getZone()).thenReturn(ZoneOffset.UTC);
        QuarkusTransaction.requiringNew().run(() ->
                entityManager.createNativeQuery("DELETE FROM verzendbudget").executeUpdate());
    }

    @Test
    void neem_binnenHetBudget_geeftHetGevraagdeAantal() {
        assertEquals(10, neem(BudgetDoel.VERZENDEN, 10));
        assertEquals(10, tokensInDatabase());
    }

    // Verzenden mag de reservering voor de navraag niet aanspreken; de navraag wel.
    @Test
    void neem_verzenden_laatDeNavraagreserveringStaan() {
        assertEquals(15, neem(BudgetDoel.VERZENDEN, 100));
        assertEquals(0, neem(BudgetDoel.VERZENDEN, 1));
        assertEquals(5, neem(BudgetDoel.NAVRAAG, 100));
        assertEquals(0, neem(BudgetDoel.NAVRAAG, 1));
    }

    @Test
    void neem_inEenNieuwTijdvak_beginntMetHetVolledigeBudget() {
        assertEquals(15, neem(BudgetDoel.VERZENDEN, 100));

        when(klok.instant()).thenReturn(TIJDVAK_B);

        assertEquals(15, neem(BudgetDoel.VERZENDEN, 100));
    }

    @Test
    void geefTerug_zetTokensTerugTotHoogstensHetBudget() {
        neem(BudgetDoel.VERZENDEN, 10);

        QuarkusTransaction.requiringNew().run(() -> verzendbudget.geefTerug(4));
        assertEquals(14, tokensInDatabase());

        QuarkusTransaction.requiringNew().run(() -> verzendbudget.geefTerug(100));
        assertEquals(20, tokensInDatabase());
    }

    @Test
    void neem_nulOfNegatief_neemtNiets() {
        assertEquals(0, neem(BudgetDoel.VERZENDEN, 0));
        assertEquals(0, neem(BudgetDoel.VERZENDEN, -3));
        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM verzendbudget").getSingleResult()).intValue()));
    }

    @Test
    void geefTerug_nul_doetNiets() {
        QuarkusTransaction.requiringNew().run(() -> verzendbudget.geefTerug(0));

        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM verzendbudget").getSingleResult()).intValue()));
    }

    @Test
    void constructor_ongeldigeConfiguratie_weigert() {
        assertThrows(IllegalStateException.class, () -> new Verzendbudget(entityManager, klok, "x", 0, 0));
        assertThrows(IllegalStateException.class, () -> new Verzendbudget(entityManager, klok, "x", 10, 10));
        assertThrows(IllegalStateException.class, () -> new Verzendbudget(entityManager, klok, "x", 10, -1));
    }

    private int neem(BudgetDoel doel, int gevraagd) {
        return QuarkusTransaction.requiringNew().call(() -> verzendbudget.neem(doel, gevraagd));
    }

    private int tokensInDatabase() {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager
                .createNativeQuery("SELECT tokens FROM verzendbudget").getSingleResult()).intValue());
    }
}
