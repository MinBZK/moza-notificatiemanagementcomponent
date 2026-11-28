package nl.rijksoverheid.moz.nmc.service.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import nl.rijksoverheid.moz.nmc.client.webhook.WebhookAdapter;
import nl.rijksoverheid.moz.nmc.domain.Cursor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class WebhookDispatcherTest {

    private static final String URL = "https://dv.example.nl/webhook";
    private static final Cursor CURSOR = new Cursor(1, 2, 3);

    private final WebhookAdapter webhookAdapter = mock(WebhookAdapter.class);
    private final WebhookSleutel webhookSleutel = mock(WebhookSleutel.class);

    // De JWT draagt de SHA-256 van precies de verzonden body, base64url zonder padding.
    @Test
    void lever_bundel_ondertekentDeHashVanDeVerzondenBody() {
        when(webhookSleutel.onderteken(anyString(), anyString(), anyString())).thenReturn("jwt");

        new WebhookDispatcher(webhookAdapter, webhookSleutel, new ObjectMapper()).lever(URL, List.of(), CURSOR);

        verify(webhookSleutel).onderteken(URL, "T1PNoYwrqgwDVLtfmj7L5e0Sq02OEbqHPC8RFhICuUU", CURSOR.codeer());
        verify(webhookAdapter).lever(URL, "Bearer jwt", CURSOR.codeer(), "[]");
    }

    // Kan alleen ontstaan met een andere mapper dan die van Quarkus; dan gaat er niets naar de webhook.
    @Test
    void lever_bundelNietTeSerialiseren_gooitEenIllegalStateExceptionEnLevertNiets() {
        ObjectMapper kapot = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                throw new JsonMappingException(null, "kapot");
            }
        };

        IllegalStateException fout = assertThrows(
                IllegalStateException.class,
                () -> new WebhookDispatcher(webhookAdapter, webhookSleutel, kapot).lever(URL, List.of(), CURSOR));

        assertInstanceOf(JsonProcessingException.class, fout.getCause());
        assertEquals("De CloudEvents-bundel is niet te serialiseren", fout.getMessage());
        verifyNoInteractions(webhookSleutel, webhookAdapter);
    }
}
