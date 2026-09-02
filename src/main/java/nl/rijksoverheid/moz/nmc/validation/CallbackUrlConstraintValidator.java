package nl.rijksoverheid.moz.nmc.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import nl.rijksoverheid.moz.nmc.service.CallbackUrlValidator;
import nl.rijksoverheid.moz.nmc.service.OngeldigeCallbackUrlException;

import java.net.URI;

/**
 * Adapter that exposes {@link CallbackUrlValidator} as a bean validation constraint, so
 * the rules keep their own plain unit test and stay callable without a validator factory.
 */
public class CallbackUrlConstraintValidator implements ConstraintValidator<ValidCallbackUrl, URI> {

    @Override
    public boolean isValid(URI callbackUrl, ConstraintValidatorContext context) {
        if (callbackUrl == null) {
            return true;
        }
        try {
            CallbackUrlValidator.valideer(callbackUrl);
            return true;
        } catch (OngeldigeCallbackUrlException e) {
            // Replaces the generic default message with the reden for this URL. The redenen
            // are constants, so they carry nothing that the message interpolator expands.
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(e.getMessage()).addConstraintViolation();
            return false;
        }
    }
}
