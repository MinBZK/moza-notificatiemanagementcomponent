package nl.rijksoverheid.moz.nmc.client.profielservice;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.api.ProfielApi;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.ContactgegevenResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.PartijRequest;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.PartijResponse;
import nl.rijksoverheid.moz.nmc.common.IdentificatieType;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;

@ApplicationScoped
public class ProfielServiceAdapter {

    private final ProfielApi profielApi;

    public ProfielServiceAdapter(@RestClient ProfielApi profielApi) {
        this.profielApi = profielApi;
    }

    public String zoekEmailAdres(IdentificatieType identificatieType, String identificatieNummer,
                                  String dienstverlener, String dienst) {
        PartijResponse partij = zoekPartij(identificatieType, identificatieNummer, dienstverlener, dienst);

        List<ContactgegevenResponse> emails = partij.getContactgegevens() == null
                ? List.of()
                : partij.getContactgegevens().stream()
                        .filter(c -> c.getType() == ContactgegevenResponse.TypeEnum.EMAIL)
                        .toList();

        return emails.stream().filter(c -> heeftExacteScope(c, dienstverlener, dienst)).findFirst()
                .or(() -> emails.stream().filter(c -> heeftDienstverlenerScope(c, dienstverlener)).findFirst())
                .or(() -> emails.stream().filter(c -> Boolean.TRUE.equals(c.getIsDefault())).findFirst())
                .or(() -> emails.stream().filter(c -> c.getScopes() == null || c.getScopes().isEmpty()).findFirst())
                .map(ContactgegevenResponse::getWaarde)
                .orElseThrow(() -> Problems.badRequest("Geen e-mailadres gevonden",
                        "Voor de opgegeven partij kon geen notificatie worden verstuurd"));
    }

    private PartijResponse zoekPartij(IdentificatieType identificatieType, String identificatieNummer,
                                       String dienstverlener, String dienst) {
        PartijRequest partijRequest = new PartijRequest()
                .identificatieType(PartijRequest.IdentificatieTypeEnum.valueOf(identificatieType.name()))
                .identificatieNummer(identificatieNummer)
                .dienstverlener(dienstverlener)
                .dienstNaam(dienst);

        try {
            return profielApi.apiProfielserviceV1PartijPost(partijRequest);
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
                throw Problems.badRequest("Partij niet gevonden",
                        "Voor de opgegeven partij kon geen notificatie worden verstuurd");
            }

            Log.error("Profielservice gaf status " + e.getResponse().getStatus() + " terug", e);
            throw Problems.serverError("Profielservice fout",
                    "Er is een fout opgetreden bij het ophalen van de contactgegevens");
        }
    }

    private boolean heeftExacteScope(ContactgegevenResponse email, String dienstverlener, String dienst) {
        return email.getScopes() != null && email.getScopes().stream()
                .anyMatch(s -> dienstverlener.equals(s.getDienstverlenerNaam()) && dienst.equals(s.getDienstNaam()));
    }

    private boolean heeftDienstverlenerScope(ContactgegevenResponse email, String dienstverlener) {
        return email.getScopes() != null && email.getScopes().stream()
                .anyMatch(s -> dienstverlener.equals(s.getDienstverlenerNaam()) && (s.getDienstNaam() == null || s.getDienstNaam().isBlank()));
    }
}
