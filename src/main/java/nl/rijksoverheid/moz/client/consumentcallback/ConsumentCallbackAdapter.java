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
import java.util.function.Function;

@ApplicationScoped
public class ConsumentCallbackAdapter {

    private final NotificatieRepository notificatieRepository;
    private final Function<String, ConsumentCallbackClient> clientFactory;
    private final long initieleWachtMs;

    public ConsumentCallbackAdapter(NotificatieRepository notificatieRepository) {
        this(notificatieRepository,
                url -> QuarkusRestClientBuilder.newBuilder()
                        .baseUri(URI.create(url))
                        .build(ConsumentCallbackClient.class),
                1000L);
    }

    ConsumentCallbackAdapter(NotificatieRepository notificatieRepository,
                              Function<String, ConsumentCallbackClient> clientFactory,
                              long initieleWachtMs) {
        this.notificatieRepository = notificatieRepository;
        this.clientFactory = clientFactory;
        this.initieleWachtMs = initieleWachtMs;
    }

    // TODO: this HTTP call happens inside the active @Transactional context from
    // NotificatieService.verwerkAfleverstatus(), keeping a DB connection open for the full
    // duration of the callback including retries. Under load this can exhaust the connection
    // pool — move the HTTP call outside the transaction (or use an async/event-driven approach)
    // before go-live. Additionally, if the JTA transaction times out during a retry sequence
    // the persistence context is rolled back and the Notificatie entity becomes detached;
    // the subsequent notificatieRepository.delete() call will then throw a DetachedObjectException.
    private static final int MAX_POGINGEN = 3;

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

        ConsumentCallbackClient client = clientFactory.apply(notificatie.callbackUrl);

        if (verstuurMetHerpoging(client, event, notificatie.callbackUrl)) {
            notificatieRepository.delete(notificatie);
        }
    }

    private boolean verstuurMetHerpoging(ConsumentCallbackClient client, NotificatieStatusEvent event, String callbackUrl) {
        long wachtMs = initieleWachtMs;
        for (int poging = 1; poging <= MAX_POGINGEN; poging++) {
            try {
                client.stuurStatusUpdate(event);
                return true;
            } catch (Exception e) {
                if (poging == MAX_POGINGEN) {
                    Log.errorf(e, "Consument-callback naar %s mislukt na %d pogingen — notificatie bewaard voor herpoging",
                            callbackUrl, MAX_POGINGEN);
                } else {
                    Log.warnf(e, "Consument-callback naar %s mislukt (poging %d/%d) — herpoging na %dms",
                            callbackUrl, poging, MAX_POGINGEN, wachtMs);
                    try {
                        Thread.sleep(wachtMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        Log.warnf(ie, "Consument-callback naar %s onderbroken na poging %d — notificatie bewaard", callbackUrl, poging);
                        return false;
                    }
                    wachtMs *= 2;
                }
            }
        }
        return false;
    }
}
