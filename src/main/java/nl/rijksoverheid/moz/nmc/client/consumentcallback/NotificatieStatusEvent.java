package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Reden;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Eén statusovergang als CloudEvent (NL GOV profiel), gelijk in de webhook en in de feed. {@code id}
 * is het event-id uit het eventlog, zodat een Dienstverlener die beide gebruikt hetzelfde event
 * herkent.
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
        String sequencetype,
        NotificatieData data) {

    private static final String TYPE_PREFIX = "nl.overheid.moz.notificatie.status.";

    public static NotificatieStatusEvent van(Event event) {
        return van(event.getId(), event.getNotificatieId(), event.getVolgnummer(), event.getVan(), event.getNaar(),
                event.getReden(), event.getTijdstip());
    }

    public static NotificatieStatusEvent van(long eventId, UUID notificatieId, long versie, NotificatieStatus van,
                                             NotificatieStatus naar, Reden reden, OffsetDateTime tijdstip) {
        return new NotificatieStatusEvent(
                "1.0",
                String.valueOf(eventId),
                TYPE_PREFIX + naar.toApiValue(),
                "/api/nmc/v1/notificaties/" + notificatieId,
                notificatieId.toString(),
                tijdstip,
                "application/json",
                // De sequence-extensie van CloudEvents schrijft een string voor.
                String.valueOf(versie),
                "Integer",
                new NotificatieData(van, naar, reden, versie));
    }
}
