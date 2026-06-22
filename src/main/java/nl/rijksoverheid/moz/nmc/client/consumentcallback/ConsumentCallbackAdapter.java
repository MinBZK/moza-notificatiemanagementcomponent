package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@ApplicationScoped
public class ConsumentCallbackAdapter {

    // TODO #732 (zie https://github.com/MinBZK/MijnOverheidZakelijk/issues/732): this HTTP call happens inside the active @Transactional context from
    // NotificatieService.verwerkAfleverstatus(), keeping a DB connection open for the full
    // duration of the callback including retries. Under load this can exhaust the connection
    // pool — move the HTTP call outside the transaction (or use an async/event-driven approach)
    // before go-live.
    private static final int MAX_POGINGEN = 3;

    private final ConsumentCallbackClientFactory clientFactory;
    private long initieleWachtMs = 1000L;

    public ConsumentCallbackAdapter(ConsumentCallbackClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    void setInitieleWachtMs(long initieleWachtMs) {
        this.initieleWachtMs = initieleWachtMs;
    }

    // Zonder callbackUrl blijft de notificatie voor altijd staan (nooit verwijderd) — voor nu
    // acceptabel, maar vraagt later om een lastUpdated-veld en cleanup-job.
    public boolean stuurStatusUpdate(Notificatie notificatie) {
        if (notificatie.callbackUrl == null) {
            return false;
        }

        NotificatieStatusEvent event = new NotificatieStatusEvent(
                "1.0",
                UUID.randomUUID(),
                "nl.rijksoverheid.moz.nmc.notificatie.status",
                "/api/nmc/v1/notificaties/" + notificatie.id,
                "notificatie/" + notificatie.id,
                OffsetDateTime.now(ZoneOffset.UTC),
                "application/json",
                new NotificatieStatusData(notificatie.id, notificatie.status));

        ConsumentCallbackClient client = clientFactory.maakClient(notificatie.callbackUrl);

        return verstuurSuccesvol(client, event, notificatie.callbackUrl);
    }

    private boolean verstuurSuccesvol(ConsumentCallbackClient client, NotificatieStatusEvent event, String callbackUrl) {
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
