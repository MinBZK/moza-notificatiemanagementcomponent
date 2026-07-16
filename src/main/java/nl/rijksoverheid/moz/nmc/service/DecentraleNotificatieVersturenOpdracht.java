package nl.rijksoverheid.moz.nmc.service;

import java.util.Map;

// Service-laag-eigen representatie van een decentrale verstuur-aanvraag, ontkoppeld van de API-laag
// (DecentraleNotificatieAanvraagRequest) zodat de service niet afhankelijk is van gegenereerde API-DTO's.
public record DecentraleNotificatieVersturenOpdracht(
        String emailAdres,
        String templateId,
        Map<String, String> berichtgegevens,
        String callbackUrl) {
}
