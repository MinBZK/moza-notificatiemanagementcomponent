package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.repository.VerzendbudgetRepository;
import nl.rijksoverheid.moz.nmc.testhelper.TaakFixtures;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.BudgetDoel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

// %test: 20 tokens per minuut, 10 gereserveerd voor het verzenden en 5 voor de navraag, 5 gedeeld.
@QuarkusTest
class VerzendbudgetTest {

    private static final Instant TIJDVAK_A = Instant.parse("2030-01-01T10:00:30Z");
    private static final Instant TIJDVAK_B = Instant.parse("2030-01-01T10:01:00Z");

    @Inject
    Verzendbudget verzendbudget;

    @Inject
    EntityManager entityManager;

    @Inject
    VerzendbudgetRepository verzendbudgetRepository;

    @InjectMock
    Clock clock;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(TIJDVAK_A);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        QuarkusTransaction.requiringNew().run(() -> TaakFixtures.leegVerzendbudget(entityManager));
    }

    @Test
    void neem_binnenHetBudget_geeftHetGevraagdeAantal() {
        assertEquals(10, neem(BudgetDoel.VERZENDEN, 10));
        assertEquals(10, tokensInDatabase());
    }

    // Verzenden mag de reservering voor de navraag niet aanspreken.
    @Test
    void neem_verzenden_laatDeNavraagreserveringStaan() {
        assertEquals(15, neem(BudgetDoel.VERZENDEN, 100));
        assertEquals(0, neem(BudgetDoel.VERZENDEN, 1));
        assertEquals(5, neem(BudgetDoel.NAVRAAG, 100));
        assertEquals(0, neem(BudgetDoel.NAVRAAG, 1));
    }

    // De navraag mag de reservering voor het verzenden niet aanspreken, de gedeelde ruimte wel.
    @Test
    void neem_navraag_laatDeVerzendreserveringStaan() {
        assertEquals(10, neem(BudgetDoel.NAVRAAG, 100));
        assertEquals(0, neem(BudgetDoel.NAVRAAG, 1));
        assertEquals(10, neem(BudgetDoel.VERZENDEN, 100));
        assertEquals(0, neem(BudgetDoel.VERZENDEN, 1));
    }

    // Wat boven de reserveringen over is, gaat naar wie het eerst claimt.
    @Test
    void neem_gedeeldeRuimte_gaatNaarWieHetEerstClaimt() {
        assertEquals(12, neem(BudgetDoel.VERZENDEN, 12));
        assertEquals(8, neem(BudgetDoel.NAVRAAG, 100));
        assertEquals(0, neem(BudgetDoel.VERZENDEN, 1));
    }

    @Test
    void geefTerug_navraag_komtTerugInDeReserveringVanDeNavraag() {
        Verzendbudget.Genomen genomen = QuarkusTransaction.requiringNew().call(() -> verzendbudget.neem(BudgetDoel.NAVRAAG, 5));

        QuarkusTransaction.requiringNew().run(() -> verzendbudget.geefTerug(genomen, 3));

        assertEquals(15, neem(BudgetDoel.VERZENDEN, 100));
        assertEquals(3, neem(BudgetDoel.NAVRAAG, 100));
    }

    // Teruggegeven tokens vullen eerst de gedeelde ruimte bij: 15 genomen is 10 reservering plus 5
    // gedeeld, dus 3 terug is gedeeld en 7 terug is 5 gedeeld plus 2 reservering.
    @Test
    void geefTerug_verzenden_vultEerstDeGedeeldeRuimteBij() {
        Verzendbudget.Genomen genomen = QuarkusTransaction.requiringNew().call(() -> verzendbudget.neem(BudgetDoel.VERZENDEN, 15));

        QuarkusTransaction.requiringNew().run(() -> verzendbudget.geefTerug(genomen, 3));

        assertEquals(8, neem(BudgetDoel.NAVRAAG, 100));
        assertEquals(0, neem(BudgetDoel.VERZENDEN, 1));
    }

    @Test
    void geefTerug_verzenden_meerDanHetGedeeldeDeel_komtInDeEigenReservering() {
        Verzendbudget.Genomen genomen = QuarkusTransaction.requiringNew().call(() -> verzendbudget.neem(BudgetDoel.VERZENDEN, 15));

        QuarkusTransaction.requiringNew().run(() -> verzendbudget.geefTerug(genomen, 7));

        assertEquals(10, neem(BudgetDoel.NAVRAAG, 100));
        assertEquals(2, neem(BudgetDoel.VERZENDEN, 100));
    }

    // De ander heeft intussen genomen; de teruggave mag de reserveringen niet boven de tokens brengen.
    @Test
    void geefTerug_naEenClaimVanDeAnder_houdtDeReserveringenBinnenDeTokens() {
        Verzendbudget.Genomen genomen = QuarkusTransaction.requiringNew().call(() -> verzendbudget.neem(BudgetDoel.VERZENDEN, 15));
        assertEquals(5, neem(BudgetDoel.NAVRAAG, 100));

        QuarkusTransaction.requiringNew().run(() -> verzendbudget.geefTerug(genomen, 15));

        assertEquals(15, tokensInDatabase());
        assertEquals(5, neem(BudgetDoel.NAVRAAG, 100));
        assertEquals(10, neem(BudgetDoel.VERZENDEN, 100));
    }

    @Test
    void neem_inEenNieuwTijdvak_beginntMetHetVolledigeBudget() {
        assertEquals(15, neem(BudgetDoel.VERZENDEN, 100));

        when(clock.instant()).thenReturn(TIJDVAK_B);

        assertEquals(15, neem(BudgetDoel.VERZENDEN, 100));
    }

    @Test
    void geefTerug_zetHoogstensHetGenomenAantalTerug() {
        Verzendbudget.Genomen genomen = QuarkusTransaction.requiringNew().call(() -> verzendbudget.neem(BudgetDoel.VERZENDEN, 10));

        QuarkusTransaction.requiringNew().run(() -> verzendbudget.geefTerug(genomen, 4));
        assertEquals(14, tokensInDatabase());

        QuarkusTransaction.requiringNew().run(() -> verzendbudget.geefTerug(genomen, 100));
        assertEquals(20, tokensInDatabase());
    }

    // Een claim die over de minuutgrens loopt, mag zijn ongebruikte tokens niet in het nieuwe
    // tijdvak stoppen: dat zou meer verzendingen toelaten dan de limiet.
    @Test
    void geefTerug_naDeMinuutgrens_laatHetNieuweTijdvakOngemoeid() {
        Verzendbudget.Genomen genomen = QuarkusTransaction.requiringNew().call(() -> verzendbudget.neem(BudgetDoel.VERZENDEN, 10));
        when(clock.instant()).thenReturn(TIJDVAK_B);
        assertEquals(15, neem(BudgetDoel.VERZENDEN, 15));

        QuarkusTransaction.requiringNew().run(() -> verzendbudget.geefTerug(genomen, 10));

        assertEquals(5, QuarkusTransaction.requiringNew().call(() ->
                TaakFixtures.tokens(entityManager, java.time.OffsetDateTime.ofInstant(TIJDVAK_B, ZoneOffset.UTC))));
    }

    @Test
    void neem_nulOfNegatief_neemtNiets() {
        assertEquals(0, neem(BudgetDoel.VERZENDEN, 0));
        assertEquals(0, neem(BudgetDoel.VERZENDEN, -3));
        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> TaakFixtures.telBudgetrijen(entityManager)));
    }

    @Test
    void geefTerug_nul_doetNiets() {
        QuarkusTransaction.requiringNew().run(() -> verzendbudget.geefTerug(
                new Verzendbudget.Genomen(0, java.time.OffsetDateTime.ofInstant(TIJDVAK_A, ZoneOffset.UTC), BudgetDoel.VERZENDEN, 0), 0));

        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> TaakFixtures.telBudgetrijen(entityManager)));
    }

    @Test
    void constructor_ongeldigeConfiguratie_weigert() {
        assertThrows(IllegalStateException.class, () -> new Verzendbudget(verzendbudgetRepository, clock, "x", 0, 0, 0));
        assertThrows(IllegalStateException.class, () -> new Verzendbudget(verzendbudgetRepository, clock, "x", 10, 6, 5));
        assertThrows(IllegalStateException.class, () -> new Verzendbudget(verzendbudgetRepository, clock, "x", 10, 5, -1));
        assertThrows(IllegalStateException.class, () -> new Verzendbudget(verzendbudgetRepository, clock, "x", 10, 0, 5));
        assertDoesNotThrow(() -> new Verzendbudget(verzendbudgetRepository, clock, "x", 10, 5, 5));
    }

    private int neem(BudgetDoel doel, int gevraagd) {
        return QuarkusTransaction.requiringNew().call(() -> verzendbudget.neem(doel, gevraagd).aantal());
    }

    private int tokensInDatabase() {
        return QuarkusTransaction.requiringNew().call(() -> TaakFixtures.tokens(entityManager));
    }
}
