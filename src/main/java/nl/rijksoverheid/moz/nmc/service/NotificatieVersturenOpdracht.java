package nl.rijksoverheid.moz.nmc.service;

import nl.rijksoverheid.moz.nmc.controller.IdentificatieType;

import java.util.Map;

public record NotificatieVersturenOpdracht(
        IdentificatieType identificatieType,
        String identificatieNummer,
        String dienstverlener,
        String dienst,
        String templateId,
        Map<String, String> berichtgegevens,
        String callbackUrl) {
}
 