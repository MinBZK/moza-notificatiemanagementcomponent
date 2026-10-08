package nl.rijksoverheid.moz.nmc.client.notifynl;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.GetMessageDataApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

class NotifyNLVerzendAdapterTest {

    private static final String TEST_TEMPLATE_ID = "00000000-0000-0000-0000-000000000001";

    private SendAMessageApi sendAMessageApi;
    private NotifyNLJwtFactory notifyNLJwtFactory;
    private GetMessageDataApi getMessageDataApi;
    private NotifyNLAuthorizationHolder authorizationHolder;
    private NotifyNLVerzendAdapter adapter;

    @BeforeEach
    void setUp() {
        sendAMessageApi = Mockito.mock(SendAMessageApi.class);
        getMessageDataApi = Mockito.mock(GetMessageDataApi.class);
        notifyNLJwtFactory = Mockito.mock(NotifyNLJwtFactory.class);
        authorizationHolder = new NotifyNLAuthorizationHolder();
        adapter = new NotifyNLVerzendAdapter(sendAMessageApi, getMessageDataApi, notifyNLJwtFactory, authorizationHolder,
                Optional.of("test-key"));

        Mockito.when(notifyNLJwtFactory.authorizationHeader(any())).thenReturn("Bearer test-token");
    }

    @Test
    void verstuurEmail_succesvol_retourneertId() throws NotifyNLConfiguratieException, NotifyNLVerzendException {
        UUID notifyNlId = UUID.randomUUID();
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(new SendEmailResponse().id(notifyNlId.toString()));

        UUID result = adapter.verstuurEmail("burger@example.nl", TEST_TEMPLATE_ID, Map.of("naam", "Voorbeeld BV"));

        assertEquals(notifyNlId, result);
    }

    @Test
    void verstuurEmail_nullBerichtgegevens_werktZonderPersonalisatie() throws NotifyNLConfiguratieException, NotifyNLVerzendException {
        UUID notifyNlId = UUID.randomUUID();
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(new SendEmailResponse().id(notifyNlId.toString()));

        UUID result = adapter.verstuurEmail("burger@example.nl", TEST_TEMPLATE_ID, null);

        assertEquals(notifyNlId, result);
    }

    @Test
    void verstuurEmail_zetBearerTokenZonderPrefixOpHolder() throws NotifyNLConfiguratieException, NotifyNLVerzendException {
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(new SendEmailResponse().id(UUID.randomUUID().toString()));

        adapter.verstuurEmail("burger@example.nl", TEST_TEMPLATE_ID, Map.of());

        assertEquals("test-token", authorizationHolder.getBearerToken().orElse(null));
    }

    @Test
    void verstuurEmail_ongeldigeApiKey_gooitNotifyNLConfiguratieException() {
        Mockito.when(notifyNLJwtFactory.authorizationHeader(any())).thenThrow(new IllegalArgumentException("Ongeldige key"));

        assertThrows(NotifyNLConfiguratieException.class,
                () -> adapter.verstuurEmail("burger@example.nl", TEST_TEMPLATE_ID, Map.of()));
    }

    @Test
    void verstuurEmail_notifyNlFout_gooitNotifyNLVerzendException() {
        Mockito.when(sendAMessageApi.sendEmail(any()))
                .thenThrow(new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).build()));

        assertThrows(NotifyNLVerzendException.class,
                () -> adapter.verstuurEmail("burger@example.nl", TEST_TEMPLATE_ID, Map.of()));
    }

    @Test
    void verstuurEmail_validationErrorOpHetAdres_isAdresafwijzing() {
        Mockito.when(sendAMessageApi.sendEmail(any())).thenThrow(new WebApplicationException(fout(400, "{\"errors\":[{\"error\":\"ValidationError\",\"message\":\"email_address Not a valid email address\"}],\"status_code\":400}")));

        NotifyNLVerzendException e = assertThrows(NotifyNLVerzendException.class,
                () -> adapter.verstuurEmail("geen-adres", TEST_TEMPLATE_ID, Map.of()));

        assertTrue(e.adresAfgewezen());
    }

    // Een team-key weigert elke ontvanger buiten de whitelist met een 400; dat ligt niet aan het adres.
    @Test
    void verstuurEmail_andere400_isGeenAdresafwijzing() {
        Mockito.when(sendAMessageApi.sendEmail(any())).thenThrow(new WebApplicationException(fout(400, "{\"errors\":[{\"error\":\"BadRequestError\",\"message\":\"Can't send to this recipient using a team-only API key\"}],\"status_code\":400}")));

        NotifyNLVerzendException e = assertThrows(NotifyNLVerzendException.class,
                () -> adapter.verstuurEmail("burger@example.nl", TEST_TEMPLATE_ID, Map.of()));

        assertFalse(e.adresAfgewezen());
        assertEquals(Optional.of(400), e.status());
    }

    @Test
    void isAdresAfwijzing_zonderBodyOngeldigeBodyOfAndereStatus_isGeenAdresafwijzing() {
        assertFalse(NotifyNLVerzendAdapter.isAdresAfwijzing(Response.status(400).build()));
        assertFalse(NotifyNLVerzendAdapter.isAdresAfwijzing(fout(400, "geen json")));
        assertFalse(NotifyNLVerzendAdapter.isAdresAfwijzing(fout(403, "{\"errors\":[{\"error\":\"ValidationError\",\"message\":\"email_address Not a valid email address\"}],\"status_code\":400}")));
    }

    private static Response fout(int status, String body) {
        return Response.status(status).entity(body).build();
    }

    @Test
    void verstuurEmail_geenId_gooitNotifyNLVerzendException() {
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(new SendEmailResponse());

        assertThrows(NotifyNLVerzendException.class,
                () -> adapter.verstuurEmail("burger@example.nl", TEST_TEMPLATE_ID, Map.of()));
    }

    @Test
    void verstuurEmail_ongeldigId_gooitNotifyNLVerzendException() {
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(new SendEmailResponse().id("niet-een-uuid"));

        assertThrows(NotifyNLVerzendException.class,
                () -> adapter.verstuurEmail("burger@example.nl", TEST_TEMPLATE_ID, Map.of()));
    }

    @Test
    void constructor_ontbrekendeApiKey_gooitIllegalStateException() {
        assertThrows(IllegalStateException.class, () -> new NotifyNLVerzendAdapter(sendAMessageApi, getMessageDataApi, notifyNLJwtFactory,
                authorizationHolder, Optional.empty()));
    }
}
