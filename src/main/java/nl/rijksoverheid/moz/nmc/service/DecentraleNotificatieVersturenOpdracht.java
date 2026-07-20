package nl.rijksoverheid.moz.nmc.service;

import java.util.Map;

public record DecentraleNotificatieVersturenOpdracht(
        String emailAdres,
        String templateId,
        Map<String, String> berichtgegevens,
        String callbackUrl) {
}
