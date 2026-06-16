package nl.rijksoverheid.moz.client.consumentcallback;

import io.quarkus.logging.Log;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.domain.Notificatie;
import nl.rijksoverheid.moz.repository.NotificatieRepository;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@ApplicationScoped
public class ConsumentCallbackAdapter {

    private final NotificatieRepository notificatieRepository;

    public ConsumentCallbackAdapter(NotificatieRepository notificatieRepository) {
        this.notificatieRepository = notificatieRepository;
    }

    // TODO: this HTTP call happens inside the active @Transactional context from
    // NotificatieService.verwerkAfleverstatus(), keeping a DB connection open for the full
    // duration of the callback. Under load this can exhaust the connection pool — move the
    // HTTP call outside the transaction (or use an async/event-driven approach) before go-live.
    public void stuurStatusUpdate(Notificatie notificatie) {
        if (notificatie.callbackUrl == null) {
            return;
        }

        NotificatieStatusEvent event = new NotificatieStatusEvent(
                "1.0",
                UUID.randomUUID().toString(),
                "nl.rijksoverheid.moz.nmc.notificatie.status",
                "/api/nmc/v1/notificaties/" + notificatie.id,
                "notificatie/" + notificatie.id,
                OffsetDateTime.now(ZoneOffset.UTC).toString(),
                "application/json",
                new NotificatieStatusData(notificatie.id, notificatie.status.toApiValue()));

        try {
            ConsumentCallbackClient client = QuarkusRestClientBuilder.newBuilder()
                    .baseUri(URI.create(notificatie.callbackUrl))
                    .build(ConsumentCallbackClient.class);
            client.stuurStatusUpdate(event);
            notificatieRepository.delete(notificatie);
        } catch (Exception e) {
            Log.errorf(e, "Consument-callback naar %s mislukt — notificatie wordt bewaard voor herpoging",
                    notificatie.callbackUrl);
        }
    }
}
