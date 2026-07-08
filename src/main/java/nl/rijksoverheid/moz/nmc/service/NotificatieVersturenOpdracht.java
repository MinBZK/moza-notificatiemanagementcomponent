package nl.rijksoverheid.moz.nmc.service;

import nl.rijksoverheid.moz.nmc.controller.IdentificatieType;

import java.util.Map;

// Service-laag-eigen representatie van een verstuur-aanvraag, ontkoppeld van de API-laag
// (NotificatieAanvraagRequest) zodat de service niet afhankelijk is van gegenereerde API-DTO's.
public record NotificatieVersturenOpdracht(
        IdentificatieType identificatieType,
        String identificatieNummer,
        String dienstverlener,
        String dienst,
        String templateId,
        Map<String, String> berichtgegevens,
        String callbackUrl) {
}
 