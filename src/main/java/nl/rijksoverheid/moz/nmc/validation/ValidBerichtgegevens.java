package nl.rijksoverheid.moz.nmc.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Begrenst de berichtgegevens vóór de endpointmethode draait: het aantal variabelen en de lengte van
 * de waarden. Het contract zet dit op het veld via {@code x-field-extra-annotation}, omdat de
 * generator {@code maxProperties} en de {@code maxLength} van de waarden van een map niet overneemt.
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = BerichtgegevensConstraintValidator.class)
public @interface ValidBerichtgegevens {

    String message() default "De berichtgegevens zijn te groot.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
