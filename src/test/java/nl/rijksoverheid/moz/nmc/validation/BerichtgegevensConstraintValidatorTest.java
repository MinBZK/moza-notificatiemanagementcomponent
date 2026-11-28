package nl.rijksoverheid.moz.nmc.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.validation.ConstraintValidatorContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class BerichtgegevensConstraintValidatorTest {

    private final BerichtgegevensConstraintValidator validator = new BerichtgegevensConstraintValidator();
    private ConstraintValidatorContext context;

    @BeforeEach
    void setUp() {
        context = mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);
    }

    // Berichtgegevens zijn optioneel; of ze verplicht zijn, bepaalt het contract met @NotNull.
    @Test
    void isValid_geenBerichtgegevens_isGeldig() {
        assertTrue(validator.isValid(null, context));
        verifyNoInteractions(context);
    }

    @Test
    void isValid_leegEnLegeWaarde_isGeldig() {
        Map<String, String> metNull = new HashMap<>();
        metNull.put("naam", null);

        assertTrue(validator.isValid(Map.of(), context));
        assertTrue(validator.isValid(metNull, context));
        assertTrue(validator.isValid(Map.of("naam", ""), context));
        verifyNoInteractions(context);
    }

    @Test
    void isValid_vijftigVariabelen_isGeldig() {
        assertTrue(validator.isValid(variabelen(50), context));
        verifyNoInteractions(context);
    }

    @Test
    void isValid_eenenvijftigVariabelen_weigertMetEigenMelding() {
        assertFalse(validator.isValid(variabelen(51), context));

        verify(context).disableDefaultConstraintViolation();
        verify(context).buildConstraintViolationWithTemplate("De berichtgegevens bevatten meer dan 50 variabelen.");
    }

    @Test
    void isValid_waardeVanTweeduizendTekens_isGeldig() {
        assertTrue(validator.isValid(Map.of("a", "kort", "b", "x".repeat(2000)), context));
        verifyNoInteractions(context);
    }

    // De melding noemt de waarde niet: de interpolator zou {…} en ${…} daarin uitwerken.
    @Test
    void isValid_waardeLangerDanTweeduizendTekens_weigertZonderDeWaardeTeNoemen() {
        assertFalse(validator.isValid(Map.of("a", "kort", "b", "${x}".repeat(500) + "x"), context));

        verify(context).disableDefaultConstraintViolation();
        verify(context)
                .buildConstraintViolationWithTemplate("Een waarde in de berichtgegevens is langer dan 2000 tekens.");
    }

    private static Map<String, String> variabelen(int aantal) {
        Map<String, String> variabelen = new HashMap<>();

        for (int i = 0; i < aantal; i++) {
            variabelen.put("v" + i, "waarde");
        }

        return variabelen;
    }
}
