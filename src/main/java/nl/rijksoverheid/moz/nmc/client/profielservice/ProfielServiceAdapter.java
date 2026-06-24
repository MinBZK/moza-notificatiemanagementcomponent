package nl.rijksoverheid.moz.nmc.client.profielservice;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.api.ProfielApi;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.ContactgegevenResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.PartijRequest;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.PartijResponse;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;
import java.util.function.Predicate;

@ApplicationScoped
public class ProfielServiceAdapter {

    private final ProfielApi profielApi;

    public ProfielServiceAdapter(@RestClient ProfielApi profielApi) {
        this.profielApi = profielApi;
    }

    public String zoekEmailAdres(@Valid PartijIdentificatie identificatie) {
        PartijResponse partij = zoekPartij(identificatie);

        List<ContactgegevenResponse> emails = partij.getContactgegevens() == null
                ? List.of()
                : partij.getContactgegevens().stream()
                        .filter(this::isEmail)
                        .toList();

        List<Predicate<ContactgegevenResponse>> prioriteiten = List.of(
                c -> heeftExacteScope(c, identificatie.dienstverlener(), identificatie.dienst()),
                c -> heeftDienstverlenerScope(c, identificatie.dienstverlener()),
                c -> Boolean.TRUE.equals(c.getIsDefault()),
                c -> c.getScopes() == null || c.getScopes().isEmpty());

        return prioriteiten.stream()
                .flatMap(p -> emails.stream().filter(p).findFirst().stream())
                .findFirst()
                .map(ContactgegevenResponse::getWaarde)
                .orElseThrow(() -> new GeenEmailadresGevondenException(
                        "Voor de opgegeven partij kon geen notificatie worden verstuurd"));
    }

    private PartijResponse zoekPartij(PartijIdentificatie identificatie) {
        PartijRequest partijRequest = new PartijRequest()
                .identificatieType(PartijRequest.IdentificatieTypeEnum.valueOf(identificatie.identificatieType().name()))
                .identificatieNummer(identificatie.identificatieNummer())
                .dienstverlener(identificatie.dienstverlener())
                .dienstNaam(identificatie.dienst());

        try {
            return profielApi.apiProfielserviceV1PartijPost(partijRequest);
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
                throw new PartijNietGevondenException("Voor de opgegeven partij kon geen notificatie worden verstuurd");
            }

            Log.error("Profielservice gaf status " + e.getResponse().getStatus() + " terug", e);
            throw new ProfielServiceException("Er is een fout opgetreden bij het ophalen van de contactgegevens", e);
        }
    }

    private boolean isEmail(ContactgegevenResponse contactgegeven) {
        return contactgegeven.getType() == ContactgegevenResponse.TypeEnum.EMAIL;
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
