package nl.rijksoverheid.moz.nmc.client.profielservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.api.ProfielApi;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.ApiProfielserviceV1VoorkeurPost201ResponseScopesInner;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.ContactgegevenResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.PartijResponse;
import nl.rijksoverheid.moz.nmc.controller.IdentificatieType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

@Tag("unit")
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
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any()))
                .thenReturn(partijMet(gescopedeEmail("scoped@example.nl", "Gemeente Voorbeeld", "Parkeervergunning")));

        String email = adapter.zoekEmailAdres(standaardIdentificatie());

        assertEquals("scoped@example.nl", email);
    }

    @Test
    void zoekEmailAdres_dienstverlenerScopeZonderDienst_wordtGekozen() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any()))
                .thenReturn(partijMet(gescopedeEmail("dv-scoped@example.nl", "Gemeente Voorbeeld", null)));

        String email = adapter.zoekEmailAdres(standaardIdentificatie());

        assertEquals("dv-scoped@example.nl", email);
    }

    @Test
    void zoekEmailAdres_verkeerdeScope_valtTerugOpDefault() {
        PartijResponse partij = new PartijResponse()
                .contactgegevens(List.of(
                        gescopedeEmail("wrong-scoped@example.nl", "Andere Dienstverlener", "Parkeervergunning"),
                        ongescopedeEmail("default@example.nl", true)));
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any())).thenReturn(partij);

        String email = adapter.zoekEmailAdres(standaardIdentificatie());

        assertEquals("default@example.nl", email);
    }

    @Test
    void zoekEmailAdres_geenScopeOfDefault_valtTerugOpOngescopedeEmail() {
        // Geen exacte/dienstverlener-scope en geen isDefault: laatste redmiddel is een volledig ongescopede email
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any()))
                .thenReturn(partijMet(ongescopedeEmail("fallback@example.nl", false)));

        String email = adapter.zoekEmailAdres(standaardIdentificatie());

        assertEquals("fallback@example.nl", email);
    }

    @Test
    void zoekEmailAdres_geenEmail_gooitGeenEmailadresGevondenException() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any()))
                .thenReturn(new PartijResponse().contactgegevens(List.of()));

        assertThrows(GeenEmailadresGevondenException.class, () -> adapter.zoekEmailAdres(standaardIdentificatie()));
    }

    @Test
    void zoekEmailAdres_profielserviceFout_gooitProfielServiceException() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any()))
                .thenThrow(new WebApplicationException(
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR).build()));

        assertThrows(ProfielServiceException.class, () -> adapter.zoekEmailAdres(standaardIdentificatie()));
    }

    // Een 400 gaat over de vorm van het verzoek van het NMC, niet over de partij.
    @Test
    void zoekEmailAdres_400_gooitProfielServiceExceptionMetStatus() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any()))
                .thenThrow(new WebApplicationException(
                        Response.status(Response.Status.BAD_REQUEST).build()));

        ProfielServiceException e =
                assertThrows(ProfielServiceException.class, () -> adapter.zoekEmailAdres(standaardIdentificatie()));

        assertEquals(Optional.of(400), e.status());
    }

    @Test
    void zoekEmailAdres_403_gooitProfielServiceExceptionMetStatus() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any()))
                .thenThrow(new WebApplicationException(
                        Response.status(Response.Status.FORBIDDEN).build()));

        ProfielServiceException e =
                assertThrows(ProfielServiceException.class, () -> adapter.zoekEmailAdres(standaardIdentificatie()));

        assertEquals(Optional.of(403), e.status());
    }

    // Een lege lijst scopes telt als ongescoped, net als geen lijst; een scope van een ander telt niet.
    @Test
    void zoekEmailAdres_legeScopes_valtTerugOpDieEmail() {
        ContactgegevenResponse leeg = ongescopedeEmail("leeg@example.nl", false).scopes(List.of());
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any()))
                .thenReturn(partijMet(gescopedeEmail("ander@example.nl", "Andere Dienstverlener", null), leeg));

        assertEquals("leeg@example.nl", adapter.zoekEmailAdres(standaardIdentificatie()));
    }

    // Een lege dienstnaam is een scope op de hele dienstverlener; die gaat vóór de default.
    @Test
    void zoekEmailAdres_dienstverlenerScopeMetLegeDienst_gaatVoorDefault() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any()))
                .thenReturn(partijMet(
                        ongescopedeEmail("default@example.nl", true),
                        gescopedeEmail("dv-scoped@example.nl", "Gemeente Voorbeeld", " ")));

        assertEquals("dv-scoped@example.nl", adapter.zoekEmailAdres(standaardIdentificatie()));
    }

    // Een scope op een andere dienst van dezelfde dienstverlener is geen scope op de hele dienstverlener.
    @Test
    void zoekEmailAdres_scopeOpAndereDienst_valtTerugOpDefault() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any()))
                .thenReturn(partijMet(
                        gescopedeEmail("andere-dienst@example.nl", "Gemeente Voorbeeld", "Hondenbelasting"),
                        ongescopedeEmail("default@example.nl", true)));

        assertEquals("default@example.nl", adapter.zoekEmailAdres(standaardIdentificatie()));
    }

    // Zonder dienst in het verzoek bestaat er geen exacte scope; de scope op de dienstverlener wint.
    @Test
    void zoekEmailAdres_zonderDienst_kiestDienstverlenerScopeBovenScopeOpEenDienst() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any()))
                .thenReturn(partijMet(
                        gescopedeEmail("dienst@example.nl", "Gemeente Voorbeeld", "Parkeervergunning"),
                        gescopedeEmail("dv-scoped@example.nl", "Gemeente Voorbeeld", null)));

        String email = adapter.zoekEmailAdres(
                new PartijIdentificatie(IdentificatieType.KVK, "12345678", "Gemeente Voorbeeld", null));

        assertEquals("dv-scoped@example.nl", email);
    }

    @Test
    void zoekEmailAdres_alleenAndereContactgegevens_gooitGeenEmailadresGevondenException() {
        ContactgegevenResponse telefoon = new ContactgegevenResponse()
                .type(ContactgegevenResponse.TypeEnum.TELEFOONNUMMER)
                .waarde("0612345678")
                .isDefault(true);
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any())).thenReturn(partijMet(telefoon));

        assertThrows(GeenEmailadresGevondenException.class, () -> adapter.zoekEmailAdres(standaardIdentificatie()));
    }

    @Test
    void zoekEmailAdres_geenContactgegevens_gooitGeenEmailadresGevondenException() {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any())).thenReturn(new PartijResponse());

        assertThrows(GeenEmailadresGevondenException.class, () -> adapter.zoekEmailAdres(standaardIdentificatie()));
    }

    // 404 en 422 gaan over de partij: een onbekend nummer.
    @ParameterizedTest
    @ValueSource(ints = {404, 422})
    void zoekEmailAdres_statusOverDePartij_gooitPartijNietGevondenException(int status) {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any()))
                .thenThrow(new WebApplicationException(Response.status(status).build()));

        PartijNietGevondenException e =
                assertThrows(PartijNietGevondenException.class, () -> adapter.zoekEmailAdres(standaardIdentificatie()));

        assertTrue(e.getMessage().contains("status " + status), e.getMessage());
    }

    @Test
    void zoekEmailAdres_nietBereikbaar_gooitProfielServiceException() {
        ProcessingException storing = new ProcessingException("time-out");
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any())).thenThrow(storing);

        ProfielServiceException e =
                assertThrows(ProfielServiceException.class, () -> adapter.zoekEmailAdres(standaardIdentificatie()));

        assertSame(storing, e.getCause());
        assertEquals(Optional.empty(), e.status());
    }

    private PartijIdentificatie standaardIdentificatie() {
        return new PartijIdentificatie(IdentificatieType.KVK, "12345678", "Gemeente Voorbeeld", "Parkeervergunning");
    }

    private PartijResponse partijMet(ContactgegevenResponse... contactgegevens) {
        return new PartijResponse().partijId(UUID.randomUUID()).contactgegevens(List.of(contactgegevens));
    }

    private ContactgegevenResponse gescopedeEmail(String email, String dienstverlenerNaam, String dienstNaam) {
        ApiProfielserviceV1VoorkeurPost201ResponseScopesInner scope =
                new ApiProfielserviceV1VoorkeurPost201ResponseScopesInner()
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
