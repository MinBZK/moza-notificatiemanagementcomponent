package nl.rijksoverheid.moz.nmc.domain;

import java.util.Objects;

/**
 * Wie de notificatie krijgt, zoals die versleuteld op de rij staat: bij decentrale regie het
 * e-mailadres van de aanroeper, bij centrale regie het identificerend nummer. Het e-mailadres uit de
 * Profielservice wordt nooit opgeslagen; de verzending haalt het op.
 *
 * @param soort  wat {@code waarde} is
 * @param waarde het e-mailadres of het identificerend nummer
 */
public record Ontvanger(Soort soort, String waarde) {

    public enum Soort {
        EMAIL,
        BSN,
        KVK,
        RSIN
    }

    public Ontvanger {
        Objects.requireNonNull(soort, "soort is verplicht");
        Objects.requireNonNull(waarde, "waarde is verplicht");
    }

    public static Ontvanger email(String adres) {
        return new Ontvanger(Soort.EMAIL, adres);
    }
}
