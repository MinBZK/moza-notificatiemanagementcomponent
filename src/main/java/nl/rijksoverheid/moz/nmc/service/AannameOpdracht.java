package nl.rijksoverheid.moz.nmc.service;

import java.util.Map;
import java.util.Objects;
import nl.rijksoverheid.moz.nmc.domain.Ontvanger;

/**
 * Een notificatieverzoek zoals de controller het aan de aanname geeft.
 *
 * @param regie           centrale of decentrale regie
 * @param ontvanger       bij decentrale regie het e-mailadres, bij centrale regie het identificerend nummer
 * @param dienstverlener  naam van de dienstverlener voor de Profielservice-lookup; bij decentrale regie leeg
 * @param dienst          naam van de dienst voor de scope van de voorkeur; mag leeg zijn
 * @param berichtType     het soort bericht dat wordt verstuurd
 * @param berichtgegevens de variabelen voor het bericht, op naam
 */
public record AannameOpdracht(
        Regie regie,
        Ontvanger ontvanger,
        String dienstverlener,
        String dienst,
        BerichtType berichtType,
        Map<String, String> berichtgegevens) {

    public AannameOpdracht {
        Objects.requireNonNull(regie, "regie is verplicht");
        Objects.requireNonNull(ontvanger, "ontvanger is verplicht");
        Objects.requireNonNull(berichtType, "berichtType is verplicht");
    }
}
