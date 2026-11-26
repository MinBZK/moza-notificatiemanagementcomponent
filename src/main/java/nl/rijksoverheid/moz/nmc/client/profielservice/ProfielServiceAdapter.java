package nl.rijksoverheid.moz.nmc.client.profielservice;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.util.List;
import java.util.function.Predicate;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.api.ProfielApi;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.ContactgegevenResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.PartijRequest;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.PartijResponse;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * De adapter naar de Profielservice: zoekt bij centrale regie het e-mailadres van een partij op. Uit de
 * contactgegevens kiest hij eerst een e-mailadres met exact de scope Dienstverlener plus dienst, dan een
 * met alleen de Dienstverlener als scope, dan het default-adres en dan een adres zonder scopes. Is er
 * geen, dan gooit hij een {@link GeenEmailadresGevondenException}.
 * <p>
 * Alleen een 404 of 422 gaat over de partij en wordt een {@link PartijNietGevondenException}. Elke
 * andere status, ook een 400, en een verbindingsfout worden een {@link ProfielServiceException}: een
 * 400 ligt aan het verzoek van het NMC, en een gewijzigd contract zou anders elke centrale notificatie
 * op een eindstatus zetten. Daarom weigert de intake al een {@code dienstverlener} of {@code dienst}
 * zonder leesbaar teken of met een regeleinde, die de Profielservice met een 400 afwijst.
 */
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
                : partij.getContactgegevens().stream().filter(this::isEmail).toList();

        List<Predicate<ContactgegevenResponse>> prioriteiten = List.of(
                c -> heeftExacteScope(c, identificatie.dienstverlener(), identificatie.dienst()),
                c -> heeftDienstverlenerScope(c, identificatie.dienstverlener()),
                c -> Boolean.TRUE.equals(c.getIsDefault()),
                c -> c.getScopes() == null || c.getScopes().isEmpty());

        return prioriteiten.stream()
                .flatMap(p -> emails.stream().filter(p).findFirst().stream())
                .findFirst()
                .map(ContactgegevenResponse::getWaarde)
                .orElseThrow(
                        () -> new GeenEmailadresGevondenException(
                                "Voor de opgegeven identificatie konden geen e-mailadressen worden gevonden in de profielgegevens van de partij"));
    }

    private PartijResponse zoekPartij(PartijIdentificatie identificatie) {
        PartijRequest partijRequest = new PartijRequest()
                .identificatieType(PartijRequest.IdentificatieTypeEnum.valueOf(
                        identificatie.identificatieType().name()))
                .identificatieNummer(identificatie.identificatieNummer())
                .dienstverlener(identificatie.dienstverlener())
                .dienstNaam(identificatie.dienst());

        try {
            return profielApi.apiProfielserviceV1PartijPost(partijRequest);
        } catch (WebApplicationException e) {
            int status = e.getResponse().getStatus();

            // 404 en 422 gaan over de partij: een onbekend nummer. Een 400 gaat over de vorm van het
            // verzoek van het NMC en zegt niets over de partij.
            if (status == 404 || status == 422) {
                throw new PartijNietGevondenException(
                        "Geen partij gevonden met de opgegeven identificatie (status " + status + ")");
            }

            Log.error("Profielservice gaf status " + e.getResponse().getStatus() + " terug", e);
            throw new ProfielServiceException("Er is een fout opgetreden bij het ophalen van de contactgegevens", e);
        } catch (ProcessingException e) {
            // Verbindingsfout of time-out: een storing, geen uitkomst over de partij.
            Log.error("Profielservice niet bereikbaar", e);
            throw new ProfielServiceException("De Profielservice was niet bereikbaar", e);
        }
    }

    private boolean isEmail(ContactgegevenResponse contactgegeven) {
        return contactgegeven.getType() == ContactgegevenResponse.TypeEnum.EMAIL;
    }

    private boolean heeftExacteScope(ContactgegevenResponse email, String dienstverlener, String dienst) {
        return dienst != null
                && email.getScopes() != null
                && email.getScopes().stream()
                        .anyMatch(s ->
                                dienstverlener.equals(s.getDienstverlenerNaam()) && dienst.equals(s.getDienstNaam()));
    }

    private boolean heeftDienstverlenerScope(ContactgegevenResponse email, String dienstverlener) {
        return email.getScopes() != null
                && email.getScopes().stream()
                        .anyMatch(s -> dienstverlener.equals(s.getDienstverlenerNaam())
                                && (s.getDienstNaam() == null
                                        || s.getDienstNaam().isBlank()));
    }
}
