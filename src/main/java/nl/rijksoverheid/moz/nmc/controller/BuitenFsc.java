package nl.rijksoverheid.moz.nmc.controller;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Markeert een controller die niet door een dienstverlener over FSC wordt aangeroepen;
 * {@link FscToegangFilter} eist daar geen toegangstoken. Elke controller zonder deze annotatie is
 * alleen met een geldig token te bereiken.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BuitenFsc {
}
