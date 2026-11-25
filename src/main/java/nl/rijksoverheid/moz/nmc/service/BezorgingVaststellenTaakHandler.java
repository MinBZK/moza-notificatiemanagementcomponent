package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;

import java.util.Optional;

/**
 * Brengt een notificatie na de vaststellingstermijn van {@code bezorgd} naar {@code definitief-bezorgd}.
 * Vraagt niets na bij NotifyNL: een faalreceipt binnen de termijn heeft de taak al afgerond.
 */
@ApplicationScoped
public class BezorgingVaststellenTaakHandler implements TaakHandler {

    private final Overgangsfunctie overgangsfunctie;
    private final NotificatieRepository notificatieRepository;
    private final TaakClaimer taakClaimer;

    public BezorgingVaststellenTaakHandler(Overgangsfunctie overgangsfunctie, NotificatieRepository notificatieRepository,
                                           TaakClaimer taakClaimer) {
        this.overgangsfunctie = overgangsfunctie;
        this.notificatieRepository = notificatieRepository;
        this.taakClaimer = taakClaimer;
    }

    @Override
    public TaakSoort taakSoort() {
        return TaakSoort.BEZORGING_VASTSTELLEN;
    }

    @Override
    public TaakUitkomst voerUit(Taak taak, Lease lease) {
        QuarkusTransaction.requiringNew().run(() -> {
            stelVast(taak);
            taakClaimer.rondAf(taak);
        });

        return TaakUitkomst.alAfgerond();
    }

    private void stelVast(Taak taak) {
        // Eerst vergrendelen en dan pas lezen: een rij die al zonder lock geladen is, weigert Hibernate te
        // vergrendelen als een receipt de versie intussen ophoogde, en dat kost de taak een poging.
        Optional<Notificatie> vergrendeld = notificatieRepository.vergrendel(taak.getNotificatieId());

        if (vergrendeld.isEmpty()) {
            Log.infof("Vaststeltaak %s voor een notificatie die niet meer bestaat; afgerond", taak.getId());

            return;
        }

        Notificatie notificatie = vergrendeld.get();

        if (notificatie.getStatus() != NotificatieStatus.BEZORGD) {
            Log.infof("Notificatie %s staat op %s; vaststeltaak %s afgerond zonder overgang",
                    notificatie.getId(), notificatie.getStatus(), taak.getId());

            return;
        }

        overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.DEFINITIEF_BEZORGD, null);
    }
}
