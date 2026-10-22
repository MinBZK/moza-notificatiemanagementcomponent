package nl.rijksoverheid.moz.nmc.client.webhook;

import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Reden;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Eén statusovergang als CloudEvent (NL GOV profiel), gelijk in de webhook en in de feed. {@code id}
 * is de UUID van het event uit het eventlog, zodat een Dienstverlener die beide gebruikt hetzelfde
 * event herkent.
 */
public record NotificatieStatusEvent(
        String specversion,
        String id,
        String type,
        String source,
        String subject,
        OffsetDateTime time,
        String datacontenttype,
        String sequence,
        NotificatieData data) {

    private static final String SPECVERSION = "1.0";
    private static final String TYPE_PREFIX = "nl.mijnoverheidzakelijk.nmc.notificatie.status.";

    public static NotificatieStatusEvent van(Event event) {
        return van(event.getUuid(), event.getNotificatieId(), event.getVolgnummer(), event.getVan(), event.getNaar(),
                event.getReden(), event.getGeregistreerd());
    }

    public static NotificatieStatusEvent van(UUID eventUuid, UUID notificatieId, long versie,
                                             NotificatieStatus vorigeStatus, NotificatieStatus nieuweStatus, Reden reden,
                                             OffsetDateTime tijdstip) {
        return new NotificatieStatusEvent(
                SPECVERSION,
                eventUuid.toString(),
                TYPE_PREFIX + nieuweStatus.toApiValue(),
                Notificatie.PAD + notificatieId,
                notificatieId.toString(),
                tijdstip,
                "application/json",
                // De sequence-extensie van CloudEvents vergelijkt als tekst; voorloopnullen houden "10" na "9".
                String.format("%019d", versie),
                new NotificatieData(vorigeStatus, nieuweStatus, reden, versie));
    }
}
