package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.StatusUpdateOpdracht;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLAfleverstatus;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLConfiguratieException;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLVerzendAdapter;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLVerzendException;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.OvergangUitkomst;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * De afleverstatus-navraag: vraagt bij NotifyNL de status op van een poging waarvoor de receipt
 * uitblijft. Een uitkomst gaat door {@link ReceiptVerwerker}, zoals een receipt. Zonder uitkomst volgt
 * de volgende navraag volgens het {@link Navraagschema}; een 404 of het einde van de bewaartermijn van
 * NotifyNL brengt de poging naar {@code onbekend} en de notificatie naar {@code bezorgstatus-onbekend}.
 */
@ApplicationScoped
public class Reconciler implements TaakHandler {

    // Een tussenstatus of een status die het NMC niet kent is geen uitkomst; de navraag loopt dan door.
    private static final Set<String> UITKOMSTEN = Set.of("delivered", "permanent-failure", "temporary-failure", "technical-failure");

    private final PogingRepository pogingRepository;
    private final Overgangsfunctie overgangsfunctie;
    private final ReceiptVerwerker receiptVerwerker;
    private final TaakClaimer taakClaimer;
    private final NotifyNLVerzendAdapter verzendAdapter;
    private final Navraagschema navraagschema;
    private final Event<StatusUpdateOpdracht> statusUpdateEvent;
    private final Duration uitstel;

    public Reconciler(PogingRepository pogingRepository, Overgangsfunctie overgangsfunctie, ReceiptVerwerker receiptVerwerker,
                      TaakClaimer taakClaimer, NotifyNLVerzendAdapter verzendAdapter, Navraagschema navraagschema,
                      Event<StatusUpdateOpdracht> statusUpdateEvent,
                      @ConfigProperty(name = "nmc.taak.uitstel") Duration uitstel) {
        this.pogingRepository = pogingRepository;
        this.overgangsfunctie = overgangsfunctie;
        this.receiptVerwerker = receiptVerwerker;
        this.taakClaimer = taakClaimer;
        this.verzendAdapter = verzendAdapter;
        this.navraagschema = navraagschema;
        this.statusUpdateEvent = statusUpdateEvent;
        this.uitstel = uitstel;
    }

    @Override
    public TaakSoort soort() {
        return TaakSoort.RECONCILIEREN;
    }

    @Override
    public TaakUitkomst voerUit(Taak taak, Lease lease) {
        Navraag navraag = QuarkusTransaction.requiringNew().call(() -> leesPoging(taak));

        if (navraag == null) {
            return TaakUitkomst.alAfgerond();
        }

        lease.verleng();

        Optional<NotifyNLAfleverstatus> afleverstatus;

        try {
            afleverstatus = verzendAdapter.vraagStatusOp(navraag.notifyId());
        } catch (NotifyNLConfiguratieException | NotifyNLVerzendException e) {
            Log.warnf(e, "Navraag bij NotifyNL voor poging %s mislukt; uitgesteld", navraag.pogingId());

            return TaakUitkomst.uitgesteld(OffsetDateTime.now(ZoneOffset.UTC).plus(uitstel), false);
        }

        if (afleverstatus.isPresent() && UITKOMSTEN.contains(afleverstatus.get().status().toLowerCase(Locale.ROOT))) {
            // Eerst afronden: de verwerking ruimt open navraagtaken op en zou deze anders ook raken.
            QuarkusTransaction.requiringNew().run(() -> {
                taakClaimer.rondAf(taak);
                receiptVerwerker.verwerk(navraag.notifyId(), navraag.pogingId().toString(), afleverstatus.get().status(),
                        afleverstatus.get().tijdstip());
            });

            return TaakUitkomst.alAfgerond();
        }

        Optional<OffsetDateTime> volgende = afleverstatus.isEmpty()
                ? Optional.empty()
                : navraagschema.volgende(navraag.verzondenOp(), OffsetDateTime.now(ZoneOffset.UTC));

        if (volgende.isPresent()) {
            return TaakUitkomst.uitgesteld(volgende.get(), false);
        }

        Log.warnf("Geen afleverstatus voor poging %s (NotifyNL-id %s): %s; bezorgstatus onbekend", navraag.pogingId(),
                navraag.notifyId(), afleverstatus.isEmpty() ? "NotifyNL kent de verzending niet" : "bewaartermijn verstreken");
        QuarkusTransaction.requiringNew().run(() -> {
            taakClaimer.rondAf(taak);
            sluitAfZonderUitkomst(navraag);
        });

        return TaakUitkomst.alAfgerond();
    }

    /** @return null als er niets na te vragen valt; de taak is dan afgerond */
    private Navraag leesPoging(Taak taak) {
        String pogingId = taak.getPayload().get(VerzendTaakHandler.PAYLOAD_POGING_ID);
        Poging poging = pogingId == null ? null : pogingRepository.findById(UUID.fromString(pogingId));

        if (poging == null || poging.getStatus() != PogingStatus.VERZONDEN) {
            Log.infof("Navraagtaak %d: poging %s bestaat niet meer of heeft een uitkomst; afgerond", taak.getId(), pogingId);
            taakClaimer.rondAf(taak);

            return null;
        }

        return new Navraag(poging.getId(), poging.getNotificatieId(), poging.getNotifyId(), poging.getVerzondenOp());
    }

    // Onder de rijvergrendeling, zodat een receipt die intussen binnenkwam voorgaat.
    private void sluitAfZonderUitkomst(Navraag navraag) {
        Notificatie notificatie = overgangsfunctie.vergrendel(navraag.notificatieId());
        Poging poging = pogingRepository.findById(navraag.pogingId());
        pogingRepository.herlaad(poging);

        if (poging.getStatus() != PogingStatus.VERZONDEN) {
            return;
        }

        poging.markeerOnbekend();

        if (notificatie.getStatus() != NotificatieStatus.VERZONDEN) {
            return;
        }

        OvergangUitkomst resultaat = overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.BEZORGSTATUS_ONBEKEND, null);
        statusUpdateEvent.fire(StatusUpdateOpdracht.van(resultaat.event(), notificatie.getCallbackUrl()));
    }

    private record Navraag(UUID pogingId, UUID notificatieId, UUID notifyId, OffsetDateTime verzondenOp) {
    }
}
