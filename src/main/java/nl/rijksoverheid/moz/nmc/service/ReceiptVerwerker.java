package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.transaction.Transactional;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.StatusUpdateOpdracht;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLAfleverstatus;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.OvergangUitkomst;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import nl.rijksoverheid.moz.nmc.domain.Reden;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Verwerkt een delivery receipt van NotifyNL: legt de uitkomst op de poging vast en voert de
 * bijbehorende overgang van de notificatie uit. Receipts komen at-least-once en ongeordend binnen;
 * een herhaalde of oudere receipt verandert niets. De volgorde komt uit het tijdstip van NotifyNL;
 * een tijdstip dat verder dan {@code nmc.receipt.klokmarge} vooruit ligt op de eigen klok wordt het
 * moment van ontvangst, omdat dat tijdstip het afleverbewijs is en een tijdstip ver in de toekomst
 * anders elke latere receipt op de poging zou blokkeren.
 */
@ApplicationScoped
public class ReceiptVerwerker {

    private final PogingRepository pogingRepository;
    private final Overgangsfunctie overgangsfunctie;
    private final Event<StatusUpdateOpdracht> statusUpdateEvent;
    private final Duration klokmarge;

    public ReceiptVerwerker(PogingRepository pogingRepository, Overgangsfunctie overgangsfunctie,
                            Event<StatusUpdateOpdracht> statusUpdateEvent,
                            @ConfigProperty(name = "nmc.receipt.klokmarge") Duration klokmarge) {
        if (klokmarge.isNegative()) {
            throw new IllegalStateException("nmc.receipt.klokmarge mag niet negatief zijn, is " + klokmarge);
        }

        this.pogingRepository = pogingRepository;
        this.overgangsfunctie = overgangsfunctie;
        this.statusUpdateEvent = statusUpdateEvent;
        this.klokmarge = klokmarge;
    }

    /**
     * @param opgetreden het tijdstip van de status volgens NotifyNL, dat de volgorde van de receipts
     *        bepaalt, begrensd op de eigen klok plus de klokmarge; null als de receipt er geen heeft,
     *        dan geldt het moment van ontvangst en gaat de receipt nooit vóór een al vastgelegde uitkomst
     * @throws NotificatieNietGevondenException als geen poging dit NotifyNL-id heeft
     */
    @Transactional
    public void verwerk(UUID notifyId, String status, OffsetDateTime opgetreden) {
        Poging poging = pogingRepository.findByNotifyId(notifyId)
                .orElseThrow(() -> new NotificatieNietGevondenException(
                        "Geen poging gevonden voor NotifyNL-referentie " + notifyId));

        Optional<PogingStatus> uitkomst = NotifyNLAfleverstatus.naarUitkomst(status, notifyId);

        if (uitkomst.isEmpty()) {
            return;
        }

        // Pogingen worden pas na de rijvergrendeling gelezen, zodat twee gelijktijdige receipts
        // elkaars uitkomst zien.
        Notificatie notificatie = overgangsfunctie.vergrendel(poging.getNotificatieId());
        pogingRepository.herlaad(poging);

        OffsetDateTime ontvangen = normaliseer(OffsetDateTime.now(ZoneOffset.UTC));
        boolean verwerkt = opgetreden == null
                ? poging.verwerkReceiptZonderTijdstip(uitkomst.get(), ontvangen)
                : poging.verwerkReceipt(uitkomst.get(), begrens(normaliseer(opgetreden), ontvangen, status, notifyId));

        if (!verwerkt) {
            // Een genegeerde receipt met een andere uitkomst is het enige spoor van een scheef tijdstip.
            if (uitkomst.get() == poging.getStatus()) {
                Log.debugf("Receipt %s voor NotifyNL-referentie %s is een herhaling en wordt genegeerd", status, notifyId);
            } else {
                Log.infof("Receipt %s voor NotifyNL-referentie %s is ouder dan de vastgelegde uitkomst %s of heeft geen "
                        + "tijdstip en wordt genegeerd", status, notifyId, poging.getStatus());
            }

            return;
        }

        Overgang overgang = Overgang.bepaal(uitkomst.get());
        OvergangUitkomst resultaat = overgangsfunctie.voerUit(notificatie.getId(), overgang.status(), overgang.reden());

        if (!resultaat.isUitgevoerd()) {
            Log.debugf("Notificatie %s blijft op %s; receipt %s (NotifyNL-referentie %s) is op de poging vastgelegd",
                    notificatie.getId(), resultaat.van(), status, notifyId);

            return;
        }

        // StatusUpdateVerzender pakt dit pas ná de commit op, zodat de Dienstverlener geen status
        // krijgt die daarna terugrolt.
        statusUpdateEvent.fire(StatusUpdateOpdracht.van(resultaat.event(), notificatie.getCallbackUrl()));
    }

    private OffsetDateTime begrens(OffsetDateTime opgetreden, OffsetDateTime ontvangen, String status, UUID notifyId) {
        if (!opgetreden.isAfter(ontvangen.plus(klokmarge))) {
            return opgetreden;
        }

        Log.warnf("Receipt %s voor NotifyNL-referentie %s heeft tijdstip %s, meer dan %s na de eigen klok; het moment "
                + "van ontvangst %s geldt", status, notifyId, opgetreden, klokmarge, ontvangen);

        return ontvangen;
    }

    // Zoals de database het tijdstip bewaart, zodat een herlezen waarde gelijk vergelijkt.
    private static OffsetDateTime normaliseer(OffsetDateTime tijdstip) {
        return tijdstip.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    // Tot er herverzending is, leidt elke faaluitkomst tot een eindstatus.
    private record Overgang(NotificatieStatus status, Reden reden) {

        static Overgang bepaal(PogingStatus uitkomst) {
            return switch (uitkomst) {
                case BEZORGD -> new Overgang(NotificatieStatus.BEZORGD, null);
                case PERMANENT_MISLUKT, TIJDELIJK_MISLUKT -> new Overgang(NotificatieStatus.NIET_BEZORGBAAR, Reden.ONBEREIKBAAR);
                case TECHNISCH_MISLUKT -> new Overgang(NotificatieStatus.TECHNISCH_MISLUKT, Reden.TECHNISCH);
                case GEPLAND, VERZONDEN, ONBEKEND -> throw new IllegalArgumentException("Geen receiptuitkomst: " + uitkomst);
            };
        }
    }
}
