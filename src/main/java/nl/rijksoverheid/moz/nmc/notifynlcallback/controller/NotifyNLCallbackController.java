package nl.rijksoverheid.moz.nmc.notifynlcallback.controller;

import io.quarkus.logging.Log;
import nl.rijksoverheid.moz.nmc.notifynlcallback.api.NotifyNlCallbackApi;
import nl.rijksoverheid.moz.nmc.notifynlcallback.api.model.AfleverstatusRequest;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import nl.rijksoverheid.moz.nmc.notifynlcallback.filter.NotifyNLCallbackBeveiligd;
import nl.rijksoverheid.moz.nmc.service.NotificatieNietGevondenException;
import nl.rijksoverheid.moz.nmc.service.NotificatieService;

import jakarta.persistence.OptimisticLockException;
import org.hibernate.StaleStateException;

import java.time.OffsetDateTime;
import java.util.stream.Stream;

@NotifyNLCallbackBeveiligd
public class NotifyNLCallbackController implements NotifyNlCallbackApi {

    // Alleen voor een botsing tussen twee gelijktijdige receipts: de verliezer herleest en
    // StatusWaarde#volgtOp bepaalt opnieuw of zijn status nog nieuw is. Drie is genoeg zonder een
    // verkapte wachtrij te worden.
    private static final int MAX_POGINGEN = 3;

    // Ruim boven elke realistische inpakdiepte (transactiemanager, interceptor, JPA-provider) en
    // klein genoeg om een kringvormige oorzakenketen te begrenzen.
    private static final int MAX_OORZAAKDIEPTE = 20;

    private final NotificatieService notificatieService;

    public NotifyNLCallbackController(NotificatieService notificatieService) {
        this.notificatieService = notificatieService;
    }

    @Override
    public void verwerkAfleverstatus(AfleverstatusRequest afleverstatusRequest) {
        try {
            verwerkMetHerpogingBijBotsing(afleverstatusRequest);
        } catch (NotificatieNietGevondenException e) {
            Log.warnf("NotifyNL-callback voor onbekende notificatie (notifyNlNotificatieId=%s)",
                    afleverstatusRequest.getId());
            throw Problems.notFound("Notificatie niet gevonden", e.getMessage());
        }
    }

    // Een botsing op de optimistic lock zou anders een van de vijf herpogingen van NotifyNL kosten.
    // Hier en niet in NotificatieService, want de lock gaat pas af bij de commit van die methode.
    private void verwerkMetHerpogingBijBotsing(AfleverstatusRequest afleverstatusRequest) {
        for (int poging = 1; ; poging++) {
            try {
                notificatieService.verwerkAfleverstatus(afleverstatusRequest.getId(), afleverstatusRequest.getStatus(),
                        gebeurtenisTijdstip(afleverstatusRequest));

                return;
            } catch (NotificatieNietGevondenException e) {
                // Geen verwerkingsfout: de publieke verwerkAfleverstatus hierboven logt dit op WARN.
                throw e;
            } catch (RuntimeException e) {
                // Beide takken kosten een van de vijf herpogingen van NotifyNL, dus mogelijk blijvend
                // verlies; ze loggen apart omdat de oorzaken ander onderzoek vragen.
                if (!isGelijktijdigeSchrijfactie(e)) {
                    Log.errorf(e, "Verwerken van de delivery receipt voor NotifyNL-referentie %s mislukt "
                            + "op een fout die herhalen niet oplost", afleverstatusRequest.getId());

                    throw e;
                }

                if (poging == MAX_POGINGEN) {
                    Log.errorf(e, "Verwerken van de delivery receipt voor NotifyNL-referentie %s opgegeven "
                            + "na %d botsingen met een gelijktijdige verwerking",
                            afleverstatusRequest.getId(), MAX_POGINGEN);

                    throw e;
                }

                Log.infof("Gelijktijdige statuswijziging voor NotifyNL-referentie %s (poging %d/%d) — opnieuw proberen",
                        afleverstatusRequest.getId(), poging, MAX_POGINGEN);
            }
        }
    }

    // De oorspronkelijke exception komt ingepakt aan (RollbackException,
    // ArcTransactionRuntimeException), vandaar dat de oorzakenketen wordt afgelopen. Bewust smal:
    // alleen deze twee betekenen "iemand anders was eerder, herlees en probeer opnieuw".
    private static boolean isGelijktijdigeSchrijfactie(Throwable e) {
        // Begrensde diepte: een controle op zelfverwijzing helpt niet, want getCause() levert nooit
        // de exception zelf op, terwijl een keten die via een omweg terugwijst wél kan doorlopen.
        Throwable oorzaak = e;
        for (int diepte = 0; oorzaak != null && diepte < MAX_OORZAAKDIEPTE; diepte++) {
            if (oorzaak instanceof OptimisticLockException || oorzaak instanceof StaleStateException) {
                return true;
            }

            oorzaak = oorzaak.getCause();
        }

        return false;
    }

    // completed_at is het tijdstip van déze status; sent_at en created_at zijn terugval, en geen van
    // drieën is verplicht, dus null als niets bruikbaar is.
    private static OffsetDateTime gebeurtenisTijdstip(AfleverstatusRequest afleverstatusRequest) {
        return Stream.of(afleverstatusRequest.getCompletedAt(), afleverstatusRequest.getSentAt(),
                        afleverstatusRequest.getCreatedAt())
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
