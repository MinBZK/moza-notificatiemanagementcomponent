package nl.rijksoverheid.moz.nmc.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Map;

/**
 * Dezelfde grenzen als in het contract: ten hoogste 50 variabelen, met namen tot 100 tekens en
 * waarden tot 2000 tekens. Samen begrenzen die ook de totale omvang.
 */
public class BerichtgegevensConstraintValidator
        implements ConstraintValidator<ValidBerichtgegevens, Map<String, String>> {

    private static final int MAX_AANTAL = 50;
    private static final int MAX_NAAM = 100;
    private static final int MAX_LENGTE = 2000;

    @Override
    public boolean isValid(Map<String, String> berichtgegevens, ConstraintValidatorContext context) {
        if (berichtgegevens == null) {
            return true;
        }

        if (berichtgegevens.size() > MAX_AANTAL) {
            return weiger(context, "De berichtgegevens bevatten meer dan " + MAX_AANTAL + " variabelen.");
        }

        for (Map.Entry<String, String> variabele : berichtgegevens.entrySet()) {
            if (variabele.getKey().length() > MAX_NAAM) {
                return weiger(context, "Een naam in de berichtgegevens is langer dan " + MAX_NAAM + " tekens.");
            }

            if (variabele.getValue() != null && variabele.getValue().length() > MAX_LENGTE) {
                return weiger(context, "Een waarde in de berichtgegevens is langer dan " + MAX_LENGTE + " tekens.");
            }
        }

        return true;
    }

    // Zonder de sleutel of de waarde in de melding: de interpolator zou {…} en ${…} daarin uitwerken.
    private static boolean weiger(ConstraintValidatorContext context, String melding) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(melding).addConstraintViolation();

        return false;
    }
}
