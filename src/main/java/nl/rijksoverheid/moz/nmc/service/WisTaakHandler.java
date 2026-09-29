package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Wist de gewrapte sleutel van een notificatie de wistermijn na haar terminale status. Daarna zijn
 * ontvanger en personalisation niet meer te ontsleutelen en is de rij pseudoniem; de rij zelf verdwijnt
 * pas na de bewaartermijn van het afleverbewijs.
 * <p>
 * De termijn wordt onder de rijvergrendeling opnieuw bepaald uit {@code terminaal_op}: een latere
 * terminale overgang verzet hem, en een notificatie die niet meer terminaal is wordt niet gewist.
 */
@ApplicationScoped
public class WisTaakHandler implements TaakHandler {

    private final Overgangsfunctie overgangsfunctie;
    private final NotificatieRepository notificatieRepository;
    private final TaakClaimer taakClaimer;
    private final Bewaartermijnen bewaartermijnen;

    public WisTaakHandler(Overgangsfunctie overgangsfunctie, NotificatieRepository notificatieRepository,
                          TaakClaimer taakClaimer, Bewaartermijnen bewaartermijnen) {
        this.overgangsfunctie = overgangsfunctie;
        this.notificatieRepository = notificatieRepository;
        this.taakClaimer = taakClaimer;
        this.bewaartermijnen = bewaartermijnen;
    }

    @Override
    public TaakSoort soort() {
        return TaakSoort.WISSEN;
    }

    @Override
    public TaakUitkomst voerUit(Taak taak, Lease lease) {
        QuarkusTransaction.requiringNew().run(() -> wis(taak));

        return TaakUitkomst.alAfgerond();
    }

    private void wis(Taak taak) {
        Notificatie notificatie = overgangsfunctie.vergrendel(taak.getNotificatieId());

        if (!notificatie.getStatus().isTerminaal()) {
            Log.infof("Notificatie %s staat op %s; wistaak %d afgerond zonder te wissen",
                    notificatie.getId(), notificatie.getStatus(), taak.getId());
            taakClaimer.rondAf(taak);

            return;
        }

        OffsetDateTime wissenOp = bewaartermijnen.wissenOp(notificatie.getTerminaalOp());

        if (wissenOp.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            taakClaimer.stelUit(taak, wissenOp, false);

            return;
        }

        notificatieRepository.wisSleutel(notificatie.getId());
        taakClaimer.rondAf(taak);
    }
}
