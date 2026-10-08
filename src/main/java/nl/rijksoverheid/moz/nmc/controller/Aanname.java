package nl.rijksoverheid.moz.nmc.controller;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Markeert een intake-methode: {@link AannameResponseFilter} maakt van een geslaagd antwoord een 202 met
 * een {@code Location}. Geen JAX-RS-annotatie, zodat die van de gegenereerde interface blijven gelden.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Aanname {
}
