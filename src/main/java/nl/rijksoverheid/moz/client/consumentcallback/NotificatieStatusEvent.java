package nl.rijksoverheid.moz.client.consumentcallback;

public record NotificatieStatusEvent(
        String specversion,
        String id,
        String type,
        String source,
        String subject,
        String time,
        String datacontenttype,
        NotificatieStatusData data) {
}
