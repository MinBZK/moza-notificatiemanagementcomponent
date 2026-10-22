package nl.rijksoverheid.moz.nmc.service;

import nl.rijksoverheid.moz.nmc.domain.Ontvanger;

import java.util.Map;
import java.util.Objects;

/**
 * Een notificatieverzoek zoals de controller het aan de aanname geeft.
 *
 * @param ontvanger      bij decentrale regie het e-mailadres, bij centrale regie het identificerend nummer
 * @param dienstverlener naam van de dienstverlener voor de Profielservice-lookup; bij decentrale regie leeg
 * @param dienst         naam van de dienst voor de scope van de voorkeur; mag leeg zijn
 */
public record AannameOpdracht(Regie regie,
                              Ontvanger ontvanger,
                              String dienstverlener,
                              String dienst,
                              String templateId,
                              Map<String, String> berichtgegevens) {

    public AannameOpdracht {
        Objects.requireNonNull(regie, "regie is verplicht");
        Objects.requireNonNull(ontvanger, "ontvanger is verplicht");
        Objects.requireNonNull(templateId, "templateId is verplicht");
    }
}
