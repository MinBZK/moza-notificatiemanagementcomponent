package nl.rijksoverheid.moz.common;

public enum NotificatieStatus {
    CREATED,
    SENDING,
    PENDING,
    SENT,
    DELIVERED,
    ACCEPTED,
    RECEIVED,
    CANCELLED,
    PERMANENT_FAILURE,
    TEMPORARY_FAILURE,
    TECHNICAL_FAILURE,
}
