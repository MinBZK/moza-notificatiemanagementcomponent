package nl.rijksoverheid.moz.nmc.client.notifynl;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.GetMessageDataApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.GetOneMessageResponse;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void vraagStatusOp_geeftStatusEnHetEersteBruikbareTijdstip() throws Exception {
        UUID id = UUID.randomUUID();
        Mockito.when(getMessageDataApi.getMessageData(id.toString())).thenReturn(new GetOneMessageResponse()
                .status("delivered").completedAt("onleesbaar").sentAt("2030-01-01T10:00:00Z"));

        NotifyNLAfleverstatus status = adapter.vraagStatusOp(id).orElseThrow();

        assertEquals("delivered", status.status());
        assertEquals(OffsetDateTime.parse("2030-01-01T10:00:00Z"), status.tijdstip());
    }

    @Test
    void vraagStatusOp_zonderTijdstip_geeftNull() throws Exception {
        UUID id = UUID.randomUUID();
        Mockito.when(getMessageDataApi.getMessageData(id.toString())).thenReturn(new GetOneMessageResponse().status("sending"));

        assertNull(adapter.vraagStatusOp(id).orElseThrow().tijdstip());
    }

    @Test
    void vraagStatusOp_404_isLeeg() throws Exception {
        UUID id = UUID.randomUUID();
        Mockito.when(getMessageDataApi.getMessageData(id.toString()))
                .thenThrow(new WebApplicationException(Response.status(404).build()));

        assertTrue(adapter.vraagStatusOp(id).isEmpty());
    }

    @Test
    void vraagStatusOp_storingOfGeenStatus_gooitVerzendException() {
        UUID fout = UUID.randomUUID();
        UUID onbereikbaar = UUID.randomUUID();
        UUID leeg = UUID.randomUUID();
        Mockito.when(getMessageDataApi.getMessageData(fout.toString()))
                .thenThrow(new WebApplicationException(Response.status(500).build()));
        Mockito.when(getMessageDataApi.getMessageData(onbereikbaar.toString())).thenThrow(new ProcessingException("time-out"));
        Mockito.when(getMessageDataApi.getMessageData(leeg.toString())).thenReturn(new GetOneMessageResponse());

        assertThrows(NotifyNLVerzendException.class, () -> adapter.vraagStatusOp(fout));
        assertThrows(NotifyNLVerzendException.class, () -> adapter.vraagStatusOp(onbereikbaar));
        assertThrows(NotifyNLVerzendException.class, () -> adapter.vraagStatusOp(leeg));
    }
}
