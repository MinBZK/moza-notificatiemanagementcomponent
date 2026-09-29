package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.StatusUpdateOpdracht;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.OvergangUitkomst;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;

/**
 * Brengt een notificatie na de vaststellingstermijn van {@code bezorgd} naar {@code definitief-bezorgd}.
 * Vraagt niets na bij NotifyNL: een faalreceipt binnen de termijn heeft de taak al afgerond.
 */
@ApplicationScoped
public class BezorgingVaststellenTaakHandler implements TaakHandler {

    private final Overgangsfunctie overgangsfunctie;
    private final NotificatieRepository notificatieRepository;
    private final TaakClaimer taakClaimer;
    private final Event<StatusUpdateOpdracht> statusUpdateEvent;

    public BezorgingVaststellenTaakHandler(Overgangsfunctie overgangsfunctie, NotificatieRepository notificatieRepository,
                                           TaakClaimer taakClaimer, Event<StatusUpdateOpdracht> statusUpdateEvent) {
        this.overgangsfunctie = overgangsfunctie;
        this.notificatieRepository = notificatieRepository;
        this.taakClaimer = taakClaimer;
        this.statusUpdateEvent = statusUpdateEvent;
    }

    @Override
    public TaakSoort soort() {
        return TaakSoort.BEZORGING_VASTSTELLEN;
    }

    @Override
    public TaakUitkomst voerUit(Taak taak, Lease lease) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (notificatieRepository.findById(taak.getNotificatieId()) == null) {
                Log.infof("Vaststeltaak %d voor een notificatie die niet meer bestaat; afgerond", taak.getId());
            } else {
                stelVast(taak);
            }

            taakClaimer.rondAf(taak);
        });

        return TaakUitkomst.alAfgerond();
    }

    private void stelVast(Taak taak) {
        Notificatie notificatie = overgangsfunctie.vergrendel(taak.getNotificatieId());

        if (notificatie.getStatus() != NotificatieStatus.BEZORGD) {
            Log.infof("Notificatie %s staat op %s; vaststeltaak %d afgerond zonder overgang",
                    notificatie.getId(), notificatie.getStatus(), taak.getId());

            return;
        }

        OvergangUitkomst resultaat = overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.DEFINITIEF_BEZORGD, null);
        statusUpdateEvent.fire(StatusUpdateOpdracht.van(resultaat.event(), notificatie.getCallbackUrl()));
    }
}
