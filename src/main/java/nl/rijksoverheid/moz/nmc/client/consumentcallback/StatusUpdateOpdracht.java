package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;

import java.util.Objects;
import java.util.UUID;

/**
 * Opdracht om een statusupdate naar de Dienstverlener te sturen, afgevuurd door
 * NotificatieService en pas na de commit uitgevoerd door StatusUpdateVerzender.
 * <p>
 * Bevat platte waarden en geen Notificatie: de observer draait buiten de transactie waarin de
 * notificatie geladen is, dus die zou daar detached zijn en een lazy @ElementCollection
 * (Notificatie#statusGeschiedenis) niet meer kunnen inlezen.
 */
public record StatusUpdateOpdracht(UUID notificatieId, String callbackUrl, StatusWaarde status) {

    public StatusUpdateOpdracht {
        // Hier controleren en niet pas bij het versturen: in de AFTER_SUCCESS-observer zou een NPE ná
        // de commit afgaan, waar de transactiemanager hem opslokt.
        Objects.requireNonNull(notificatieId, "notificatieId is verplicht");
        Objects.requireNonNull(status, "status is verplicht");
        // callbackUrl mag bewust null zijn: dat betekent dat de Dienstverlener geen callback heeft
        // geconfigureerd en de status zelf opvraagt.
    }
}
