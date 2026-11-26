package nl.rijksoverheid.moz.nmc.client.notifynl;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.GetMessageDataApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.GetOneMessageResponse;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailResponse;
import nl.rijksoverheid.moz.nmc.testhelper.NotifyNLFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.GetMultipleMessagesResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

@Tag("unit")
class NotifyNLVerzendAdapterTest {

    private static final String TEST_TEMPLATE_ID = "00000000-0000-0000-0000-000000000001";
    // Het poging-id, dat het NMC als reference aan NotifyNL meegeeft.
    private static final String REFERENCE = "3f1a2b4c-0000-4000-8000-00000000000a";

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

        NotifyNLFixtures.stubJwt(notifyNLJwtFactory);
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
        Mockito.when(sendAMessageApi.sendEmail(any())).thenThrow(new WebApplicationException(fout(400, NotifyNLFixtures.ADRES_AFGEWEZEN)));

        NotifyNLVerzendException e = assertThrows(NotifyNLVerzendException.class,
                () -> adapter.verstuurEmail("geen-adres", TEST_TEMPLATE_ID, Map.of()));

        assertTrue(e.isAdresAfgewezen());
    }

    // Een team-key weigert elke ontvanger buiten de whitelist met een 400; dat ligt niet aan het adres.
    @Test
    void verstuurEmail_andere400_isGeenAdresafwijzing() {
        Mockito.when(sendAMessageApi.sendEmail(any())).thenThrow(new WebApplicationException(fout(400, NotifyNLFixtures.TEAM_KEY_FOUT)));

        NotifyNLVerzendException e = assertThrows(NotifyNLVerzendException.class,
                () -> adapter.verstuurEmail("burger@example.nl", TEST_TEMPLATE_ID, Map.of()));

        assertFalse(e.isAdresAfgewezen());
        assertEquals(Optional.of(400), e.status());
    }

    @Test
    void isAdresAfwijzing_zonderBodyOngeldigeBodyOfAndereStatus_isGeenAdresafwijzing() {
        assertFalse(NotifyNLVerzendAdapter.isAdresAfwijzing(Response.status(400).build()));
        assertFalse(NotifyNLVerzendAdapter.isAdresAfwijzing(fout(400, "geen json")));
        assertFalse(NotifyNLVerzendAdapter.isAdresAfwijzing(fout(403, NotifyNLFixtures.ADRES_AFGEWEZEN)));
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

    @Test
    void zoekOpReference_verzendingMetDieReference_geeftHaarId() throws Exception {
        UUID notifyNlId = UUID.randomUUID();
        zoekresultaat(new GetOneMessageResponse().id(notifyNlId.toString()).reference(REFERENCE));

        assertEquals(Optional.of(notifyNlId), adapter.zoekOpReference(REFERENCE));
    }

    // Het id van een andere verzending overnemen zou betekenen dat de eigen e-mail nooit verstuurd wordt
    // en de status van een vreemde verzending aan de notificatie komt te hangen.
    @Test
    void zoekOpReference_alleenEenVerzendingMetEenAndereReference_isLeeg() throws Exception {
        zoekresultaat(new GetOneMessageResponse().id(UUID.randomUUID().toString()).reference("een-andere-reference"));

        assertTrue(adapter.zoekOpReference(REFERENCE).isEmpty());
    }

    @Test
    void zoekOpReference_tussenAndereVerzendingen_kiestDieMetDeEigenReference() throws Exception {
        UUID notifyNlId = UUID.randomUUID();
        zoekresultaat(
                new GetOneMessageResponse().id(UUID.randomUUID().toString()).reference("een-andere-reference"),
                new GetOneMessageResponse().id(UUID.randomUUID().toString()),
                new GetOneMessageResponse().id(notifyNlId.toString()).reference(REFERENCE));

        assertEquals(Optional.of(notifyNlId), adapter.zoekOpReference(REFERENCE));
    }

    @Test
    void zoekOpReference_geenAntwoordGeenLijstOfGeenId_isLeeg() throws Exception {
        Mockito.when(getMessageDataApi.getMultipleMessagesStatus(isNull(), isNull(), eq(REFERENCE), isNull(), isNull()))
                .thenReturn(null)
                .thenReturn(new GetMultipleMessagesResponse().notifications(null))
                .thenReturn(new GetMultipleMessagesResponse().notifications(List.of(
                        new GetOneMessageResponse().reference(REFERENCE))));

        assertTrue(adapter.zoekOpReference(REFERENCE).isEmpty(), "geen antwoord");
        assertTrue(adapter.zoekOpReference(REFERENCE).isEmpty(), "antwoord zonder lijst");
        assertTrue(adapter.zoekOpReference(REFERENCE).isEmpty(), "verzending zonder id");
    }

    @Test
    void zoekOpReference_ongeldigId_gooitOnbruikbaarAntwoord() {
        zoekresultaat(new GetOneMessageResponse().id("geen-uuid").reference(REFERENCE));

        NotifyNLVerzendException fout = assertThrows(NotifyNLVerzendException.class, () -> adapter.zoekOpReference(REFERENCE));

        assertTrue(fout.isOnbruikbaarAntwoord());
    }

    @Test
    void zoekOpReference_storingBijNotifyNL_gooitVerzendException() {
        Mockito.when(getMessageDataApi.getMultipleMessagesStatus(isNull(), isNull(), eq(REFERENCE), isNull(), isNull()))
                .thenThrow(new WebApplicationException(Response.status(500).build()))
                .thenThrow(new ProcessingException("time-out"));

        assertThrows(NotifyNLVerzendException.class, () -> adapter.zoekOpReference(REFERENCE), "een 500");
        assertThrows(NotifyNLVerzendException.class, () -> adapter.zoekOpReference(REFERENCE), "niet bereikbaar");
    }

    private void zoekresultaat(GetOneMessageResponse... verzendingen) {
        Mockito.when(getMessageDataApi.getMultipleMessagesStatus(isNull(), isNull(), eq(REFERENCE), isNull(), isNull()))
                .thenReturn(new GetMultipleMessagesResponse().notifications(List.of(verzendingen)));
    }
}
