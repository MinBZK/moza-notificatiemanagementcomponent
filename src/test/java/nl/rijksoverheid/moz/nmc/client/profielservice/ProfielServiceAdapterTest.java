package nl.rijksoverheid.moz.nmc.client.profielservice;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.api.ProfielApi;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.ApiProfielserviceV1VoorkeurPost201ResponseScopesInner;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.ContactgegevenResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.PartijResponse;
import nl.rijksoverheid.moz.nmc.common.IdentificatieType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;

class ProfielServiceAdapterTest {

    private ProfielApi profielApi;
    private ProfielServiceAdapter adapter;

    @BeforeEach
    void setUp() {
        profielApi = Mockito.mock(ProfielApi.class);
        adapter = new ProfielServiceAdapter(profielApi);
    }

    @Test
    void zoekEmailAdres_exacteScope_wordtGekozen() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any())).thenReturn(
                partijMet(gescopedeEmail("scoped@example.nl", "Gemeente Voorbeeld", "Parkeervergunning")));

        String email = adapter.zoekEmailAdres(standaardIdentificatie());

        assertEquals("scoped@example.nl", email);
    }

    @Test
    void zoekEmailAdres_dienstverlenerScopeZonderDienst_wordtGekozen() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any())).thenReturn(
                partijMet(gescopedeEmail("dv-scoped@example.nl", "Gemeente Voorbeeld", null)));

        String email = adapter.zoekEmailAdres(standaardIdentificatie());

        assertEquals("dv-scoped@example.nl", email);
    }

    @Test
    void zoekEmailAdres_verkeerdeScope_valtTerugOpDefault() {
        PartijResponse partij = new PartijResponse().contactgegevens(List.of(
                gescopedeEmail("wrong-scoped@example.nl", "Andere Dienstverlener", "Parkeervergunning"),
                ongescopedeEmail("default@example.nl", true)));
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any())).thenReturn(partij);

        String email = adapter.zoekEmailAdres(standaardIdentificatie());

        assertEquals("default@example.nl", email);
    }

    @Test
    void zoekEmailAdres_geenScopeOfDefault_valtTerugOpOngescopedeEmail() {
        // Geen exacte/dienstverlener-scope en geen isDefault: laatste redmiddel is een volledig ongescopede email
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any())).thenReturn(
                partijMet(ongescopedeEmail("fallback@example.nl", false)));

        String email = adapter.zoekEmailAdres(standaardIdentificatie());

        assertEquals("fallback@example.nl", email);
    }

    @Test
    void zoekEmailAdres_geenEmail_gooitGeenEmailadresGevondenException() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any())).thenReturn(
                new PartijResponse().contactgegevens(List.of()));

        assertThrows(GeenEmailadresGevondenException.class, () -> adapter.zoekEmailAdres(standaardIdentificatie()));
    }

    @Test
    void zoekEmailAdres_partijNietGevonden_gooitPartijNietGevondenException() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any()))
                .thenThrow(new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build()));

        assertThrows(PartijNietGevondenException.class, () -> adapter.zoekEmailAdres(standaardIdentificatie()));
    }

    @Test
    void zoekEmailAdres_profielserviceFout_gooitProfielServiceException() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any()))
                .thenThrow(new WebApplicationException(Response.status(Response.Status.INTERNAL_SERVER_ERROR).build()));

        assertThrows(ProfielServiceException.class, () -> adapter.zoekEmailAdres(standaardIdentificatie()));
    }

    private PartijIdentificatie standaardIdentificatie() {
        return new PartijIdentificatie(IdentificatieType.KVK, "12345678", "Gemeente Voorbeeld", "Parkeervergunning");
    }

    private PartijResponse partijMet(ContactgegevenResponse... contactgegevens) {
        return new PartijResponse().partijId(UUID.randomUUID()).contactgegevens(List.of(contactgegevens));
    }

    private ContactgegevenResponse gescopedeEmail(String email, String dienstverlenerNaam, String dienstNaam) {
        ApiProfielserviceV1VoorkeurPost201ResponseScopesInner scope = new ApiProfielserviceV1VoorkeurPost201ResponseScopesInner()
                .dienstverlenerNaam(dienstverlenerNaam)
                .dienstNaam(dienstNaam);

        return new ContactgegevenResponse()
                .type(ContactgegevenResponse.TypeEnum.EMAIL)
                .waarde(email)
                .scopes(List.of(scope));
    }

    private ContactgegevenResponse ongescopedeEmail(String email, boolean isDefault) {
        return new ContactgegevenResponse()
                .type(ContactgegevenResponse.TypeEnum.EMAIL)
                .waarde(email)
                .isDefault(isDefault);
    }
}
