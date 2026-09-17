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

    // Alleen bedoeld voor een botsing tussen twee gelijktijdige receipts voor dezelfde notificatie.
    // Die is per definitie kort: de verliezer herleest en ziet dan de status van de winnaar staan,
    // waarna StatusWaarde#volgtOp meestal beslist dat er niets meer te doen is. Drie is ruim genoeg
    // voor zo'n botsing en klein genoeg om geen verkapte wachtrij te worden.
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
            // Kan een late/vertraagde callback zijn voor een notificatie die de retentiejob
            // inmiddels al heeft opgeruimd (registratietijd ouder dan de bewaartermijn, ook als
            // er nog geen NotifyNL-uitkomst was) — zonder deze log is dat niet te onderscheiden van
            // een onbekende/foutieve referentie.
            Log.warnf("NotifyNL-callback voor onbekende of reeds verwijderde notificatie (notifyNlNotificatieId=%s)",
                    afleverstatusRequest.getId());
            throw Problems.notFound("Notificatie niet gevonden", e.getMessage());
        }
    }

    // Twee overlappende receipts voor dezelfde notificatie laten de verliezer stuklopen op de
    // optimistic lock. Zonder deze herpoging kost dat een van de 5 herpogingen van NotifyNL, met 5
    // minuten ertussen; na de vijfde is de receipt daar weg.
    //
    // De herpoging zit hier en niet in NotificatieService, omdat de optimistic lock pas bij de commit
    // van die @Transactional-methode afgaat en dus buiten haar eigen try/catch valt.
    private void verwerkMetHerpogingBijBotsing(AfleverstatusRequest afleverstatusRequest) {
        for (int poging = 1; ; poging++) {
            try {
                notificatieService.verwerkAfleverstatus(afleverstatusRequest.getId(), afleverstatusRequest.getStatus(),
                        gebeurtenisTijdstip(afleverstatusRequest));

                return;
            } catch (RuntimeException e) {
                // Beide takken leveren een 5xx op en kosten daarmee een van de vijf herpogingen die
                // NotifyNL doet; na de vijfde is de afleverstatus daar weg. Dat is het enige moment
                // in dit pad met blijvend verlies, dus het hoort niet ongelogd te gebeuren — en de
                // twee oorzaken vragen om verschillend onderzoek.
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

    // completed_at is "the last time the status was updated" en dus het tijdstip van déze status;
    // sent_at en created_at horen bij de verzending en zijn terugval. Geen van drieën is verplicht in
    // EmailCallbackRequest, vandaar de keten en een null als niets bruikbaar is. Hier en niet in de
    // service, want welk veld van NotifyNL wat betekent is kennis van dit koppelvlak.
    private static OffsetDateTime gebeurtenisTijdstip(AfleverstatusRequest afleverstatusRequest) {
        return Stream.of(afleverstatusRequest.getCompletedAt(), afleverstatusRequest.getSentAt(),
                        afleverstatusRequest.getCreatedAt())
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
