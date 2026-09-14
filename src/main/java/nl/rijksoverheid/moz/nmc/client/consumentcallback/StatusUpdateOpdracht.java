package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;

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
}
