package nl.rijksoverheid.moz.nmc.client.notify;

import io.quarkiverse.httpproblem.HttpProblem;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.client.notify.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notify.generated.model.SendEmailResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;

class VerzendAdapterTest {

    private SendAMessageApi sendAMessageApi;
    private NotifyJwtFactory notifyJwtFactory;
    private NotifyAuthorizationHolder authorizationHolder;
    private VerzendAdapter adapter;

    @BeforeEach
    void setUp() {
        sendAMessageApi = Mockito.mock(SendAMessageApi.class);
        notifyJwtFactory = Mockito.mock(NotifyJwtFactory.class);
        authorizationHolder = new NotifyAuthorizationHolder();
        adapter = new VerzendAdapter(sendAMessageApi, notifyJwtFactory, authorizationHolder,
                Optional.of("test-key"), Optional.of("test-template"));

        Mockito.when(notifyJwtFactory.authorizationHeader(any())).thenReturn("Bearer test-token");
    }

    @Test
    void verstuurEmail_succesvol_retourneertId() {
        UUID notifyNlId = UUID.randomUUID();
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(new SendEmailResponse().id(notifyNlId.toString()));

        UUID result = adapter.verstuurEmail("burger@example.nl", Map.of("naam", "Voorbeeld BV"));

        assertEquals(notifyNlId, result);
    }

    @Test
    void verstuurEmail_nullBerichtgegevens_werktZonderPersonalisatie() {
        UUID notifyNlId = UUID.randomUUID();
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(new SendEmailResponse().id(notifyNlId.toString()));

        UUID result = adapter.verstuurEmail("burger@example.nl", null);

        assertEquals(notifyNlId, result);
    }

    @Test
    void verstuurEmail_zetBearerTokenZonderPrefixOpHolder() {
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(new SendEmailResponse().id(UUID.randomUUID().toString()));

        adapter.verstuurEmail("burger@example.nl", Map.of());

        assertEquals("test-token", authorizationHolder.getBearerToken().orElse(null));
    }

    @Test
    void verstuurEmail_ongeldigeApiKey_gooitServerError() {
        Mockito.when(notifyJwtFactory.authorizationHeader(any())).thenThrow(new IllegalArgumentException("Ongeldige key"));

        HttpProblem problem = assertThrows(HttpProblem.class,
                () -> adapter.verstuurEmail("burger@example.nl", Map.of()));

        assertEquals(500, problem.getStatusCode());
    }

    @Test
    void verstuurEmail_notifyNlFout_gooitBadGateway() {
        Mockito.when(sendAMessageApi.sendEmail(any()))
                .thenThrow(new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).build()));

        HttpProblem problem = assertThrows(HttpProblem.class,
                () -> adapter.verstuurEmail("burger@example.nl", Map.of()));

        assertEquals(502, problem.getStatusCode());
    }

    @Test
    void verstuurEmail_geenId_gooitBadGateway() {
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(new SendEmailResponse());

        HttpProblem problem = assertThrows(HttpProblem.class,
                () -> adapter.verstuurEmail("burger@example.nl", Map.of()));

        assertEquals(502, problem.getStatusCode());
    }

    @Test
    void verstuurEmail_ongeldigId_gooitBadGateway() {
        Mockito.when(sendAMessageApi.sendEmail(any())).thenReturn(new SendEmailResponse().id("niet-een-uuid"));

        HttpProblem problem = assertThrows(HttpProblem.class,
                () -> adapter.verstuurEmail("burger@example.nl", Map.of()));

        assertEquals(502, problem.getStatusCode());
    }
}
