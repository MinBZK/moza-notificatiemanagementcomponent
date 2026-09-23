package nl.rijksoverheid.moz.nmc.client.notifynl;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;

class NotifyNLVerzendAdapterTest {

    private static final String TEST_TEMPLATE_ID = "00000000-0000-0000-0000-000000000001";

    private SendAMessageApi sendAMessageApi;
    private NotifyNLJwtFactory notifyNLJwtFactory;
    private NotifyNLAuthorizationHolder authorizationHolder;
    private NotifyNLVerzendAdapter adapter;

    @BeforeEach
    void setUp() {
        sendAMessageApi = Mockito.mock(SendAMessageApi.class);
        notifyNLJwtFactory = Mockito.mock(NotifyNLJwtFactory.class);
        authorizationHolder = new NotifyNLAuthorizationHolder();
        adapter = new NotifyNLVerzendAdapter(sendAMessageApi, notifyNLJwtFactory, authorizationHolder,
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
        assertThrows(IllegalStateException.class, () -> new NotifyNLVerzendAdapter(sendAMessageApi, notifyNLJwtFactory,
                authorizationHolder, Optional.empty()));
    }
}
