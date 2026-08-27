package nl.rijksoverheid.moz.nmc.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a callbackUrl property so it is validated before the endpoint method runs.
 * <p>
 * The contract puts this on the field via {@code x-field-extra-annotation}. A plain
 * {@code maxLength} in the contract is not an option: {@code format: uri} types the
 * property as {@link java.net.URI}, for which Hibernate Validator has no {@code @Size}
 * validator (HV000030).
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CallbackUrlConstraintValidator.class)
public @interface ValidCallbackUrl {

    String message() default "De callbackUrl is ongeldig.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
