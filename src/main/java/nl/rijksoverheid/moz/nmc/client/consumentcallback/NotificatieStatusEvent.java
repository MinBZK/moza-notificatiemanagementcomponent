package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificatieStatusEvent(
        String specversion,
        UUID id,
        String type,
        String source,
        String subject,
        OffsetDateTime time,
        String datacontenttype,
        NotificatieStatus data) {
}
