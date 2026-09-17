package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import java.util.UUID;

/**
 * De payload van het CloudEvent naar de Dienstverlener. status is een String en geen StatusWaarde:
 * dit is een extern contract, dus de waarde staat hier los van de interne enum.
 * <p>
 * TODO (buiten scope): er zit geen volgordeinformatie in, terwijl statusupdates elkaar kunnen
 * inhalen. ConsumentCallbackAdapter herprobeert synchroon met 1s en daarna 2s ertussen, dus terwijl
 * de callback van de ene receipt nog aan het herproberen is, kan die van een volgende al geslaagd
 * zijn — de Dienstverlener krijgt dan bijvoorbeeld {@code delivered} vóór {@code sending}.
 * <p>
 * Intern houdt {@code StatusWaarde#volgtOp} alleen een terugval naar de verzendfase tegen; tussen de
 * terugmeldingen van NotifyNL onderling geldt geen volgorde. De Dienstverlener heeft evenmin
 * houvast: {@code NotificatieStatusEvent#time} is het verzendmoment en niet het registratiemoment,
 * en er is geen volgnummer. Hij kan een verouderde update dus niet herkennen en laten vallen.
 * <p>
 * Op te lossen door hier de registratietijd ({@code NotificatieStatus#geregistreerd}) of het
 * volgnummer uit {@code notificatie_status} mee te sturen. Dat laatste is robuuster, want monotoon
 * per notificatie en niet afhankelijk van een klok. Beide zijn een uitbreiding van het
 * CloudEvent-contract en horen dus ook in het schema in {@code META-INF/openapi.yaml}.
 */
public record NotificatieData(UUID notificatieId, String status) {
}
