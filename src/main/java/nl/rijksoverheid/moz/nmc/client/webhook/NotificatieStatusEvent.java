package nl.rijksoverheid.moz.nmc.client.webhook;

import java.time.OffsetDateTime;
import java.util.UUID;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Reden;

/**
 * Eén statusovergang als CloudEvent (NL GOV profiel), gelijk in de webhook en in de feed. {@code id}
 * is de UUID van het event uit het eventlog, zodat een Dienstverlener die beide gebruikt hetzelfde
 * event herkent.
 *
 * @param specversion     de versie van de CloudEvents-specificatie
 * @param id              de UUID van het event
 * @param type            het soort event; eindigt op de nieuwe status als API-waarde
 * @param source          de URN van het NMC als bron van het event
 * @param subject         het id van de notificatie
 * @param time            het tijdstip waarop het NMC de overgang registreerde
 * @param datacontenttype het mediatype van {@code data}
 * @param sequence        het volgnummer van de overgang binnen de notificatie, met voorloopnullen
 * @param data            de overgang zelf: vorige en nieuwe status, reden en versie
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
    private static final String DATACONTENTTYPE = "application/json";

    public static NotificatieStatusEvent of(Event event, String source) {
        return of(
                source,
                event.getCloudEventId(),
                event.getNotificatieId(),
                event.getVolgnummer(),
                event.getVan(),
                event.getNaar(),
                event.getReden(),
                event.getGeregistreerd());
    }

    public static NotificatieStatusEvent of(
            String source,
            UUID eventUuid,
            UUID notificatieId,
            long versie,
            NotificatieStatus vorigeStatus,
            NotificatieStatus nieuweStatus,
            Reden reden,
            OffsetDateTime tijdstip) {
        return new NotificatieStatusEvent(
                SPECVERSION,
                eventUuid.toString(),
                TYPE_PREFIX + nieuweStatus.toApiValue(),
                source,
                notificatieId.toString(),
                tijdstip,
                DATACONTENTTYPE,
                // De sequence-extensie van CloudEvents vergelijkt als tekst; voorloopnullen houden "10" na "9".
                String.format("%019d", versie),
                new NotificatieData(vorigeStatus, nieuweStatus, reden, versie));
    }
}
