package nl.rijksoverheid.moz.nmc.common;

import com.fasterxml.jackson.annotation.JsonValue;

public enum NotificatieStatus {
    SENDING,
    DELIVERED,
    PERMANENT_FAILURE,
    TEMPORARY_FAILURE,
    TECHNICAL_FAILURE,
    PENDING,
    SENT,
    ACCEPTED,
    RECEIVED,
    CANCELLED,
    CREATED;

    /** Returns the kebab-case representation for use in API responses (e.g. permanent-failure). */
    @JsonValue
    public String toApiValue() {
        return name().toLowerCase().replace('_', '-');
    }
}
