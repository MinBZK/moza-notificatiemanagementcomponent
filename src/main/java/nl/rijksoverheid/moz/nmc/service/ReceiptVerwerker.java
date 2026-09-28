package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.transaction.Transactional;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.StatusUpdateOpdracht;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.OvergangUitkomst;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import nl.rijksoverheid.moz.nmc.domain.Reden;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Verwerkt een delivery receipt van NotifyNL: legt de uitkomst op de poging vast en voert de
 * bijbehorende overgang van de notificatie uit. Receipts komen at-least-once en ongeordend binnen;
 * een herhaalde of oudere receipt verandert niets.
 */
@ApplicationScoped
public class ReceiptVerwerker {

    private final PogingRepository pogingRepository;
    private final Overgangsfunctie overgangsfunctie;
    private final Event<StatusUpdateOpdracht> statusUpdateEvent;

    public ReceiptVerwerker(PogingRepository pogingRepository, Overgangsfunctie overgangsfunctie,
                            Event<StatusUpdateOpdracht> statusUpdateEvent) {
        this.pogingRepository = pogingRepository;
        this.overgangsfunctie = overgangsfunctie;
        this.statusUpdateEvent = statusUpdateEvent;
    }

    /**
     * @param opgetreden het tijdstip van de status volgens NotifyNL; bij null of een tijdstip in de
     *        toekomst geldt de eigen klok
     * @throws NotificatieNietGevondenException als geen poging dit NotifyNL-id heeft
     */
    @Transactional
    public void verwerk(UUID notifyId, String status, OffsetDateTime opgetreden) {
        Poging poging = pogingRepository.findByNotifyId(notifyId)
                .orElseThrow(() -> new NotificatieNietGevondenException(
                        "Geen poging gevonden voor NotifyNL-referentie " + notifyId));

        Optional<PogingStatus> uitkomst = parseStatus(status, notifyId);

        if (uitkomst.isEmpty()) {
            return;
        }

        // Pogingen worden pas na de rijvergrendeling gelezen, zodat twee gelijktijdige receipts
        // elkaars uitkomst zien.
        Notificatie notificatie = overgangsfunctie.vergrendel(poging.getNotificatieId());
        pogingRepository.herlaad(poging);

        if (!poging.verwerkReceipt(uitkomst.get(), begrens(opgetreden))) {
            Log.debugf("Receipt %s voor NotifyNL-referentie %s is een herhaling of ouder dan de vastgelegde "
                    + "uitkomst en wordt genegeerd", status, notifyId);

            return;
        }

        Overgang overgang = Overgang.bij(uitkomst.get());
        OvergangUitkomst resultaat = overgangsfunctie.voerUit(notificatie.getId(), overgang.naar(), overgang.reden());

        if (!resultaat.isUitgevoerd()) {
            Log.debugf("Notificatie %s blijft op %s; receipt %s (NotifyNL-referentie %s) is op de poging vastgelegd",
                    notificatie.getId(), resultaat.van(), status, notifyId);

            return;
        }

        // StatusUpdateVerzender pakt dit pas ná de commit op, zodat de Dienstverlener geen status
        // krijgt die daarna terugrolt.
        statusUpdateEvent.fire(StatusUpdateOpdracht.van(resultaat.event(), notificatie.getCallbackUrl()));
    }

    // De tussenstatussen van NotifyNL leveren geen uitkomst op. Een onbekende status wordt op ERROR
    // gelogd en verandert niets, zodat hij niet als een bekende uitkomst landt.
    private static Optional<PogingStatus> parseStatus(String status, UUID notifyId) {
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "delivered" -> Optional.of(PogingStatus.BEZORGD);
            case "permanent-failure" -> Optional.of(PogingStatus.PERMANENT_MISLUKT);
            case "temporary-failure" -> Optional.of(PogingStatus.TIJDELIJK_MISLUKT);
            case "technical-failure" -> Optional.of(PogingStatus.TECHNISCH_MISLUKT);
            case "created", "sending", "pending" -> {
                Log.debugf("Tussenstatus %s voor NotifyNL-referentie %s genegeerd", status, notifyId);
                yield Optional.empty();
            }
            default -> {
                Log.errorf("Onbekende NotifyNL-status '%s' ontvangen voor NotifyNL-referentie %s; niets vastgelegd. "
                        + "Controleer of NotifyNL nieuwe statussen is gaan sturen", status, notifyId);
                yield Optional.empty();
            }
        };
    }

    private static OffsetDateTime begrens(OffsetDateTime opgetreden) {
        OffsetDateTime nu = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime tijdstip = opgetreden == null || opgetreden.isAfter(nu) ? nu : opgetreden;

        return tijdstip.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    // Tot er herverzending is, is elke faaluitkomst terminaal.
    private record Overgang(NotificatieStatus naar, Reden reden) {

        static Overgang bij(PogingStatus uitkomst) {
            return switch (uitkomst) {
                case BEZORGD -> new Overgang(NotificatieStatus.BEZORGD, null);
                case PERMANENT_MISLUKT, TIJDELIJK_MISLUKT -> new Overgang(NotificatieStatus.NIET_BEZORGBAAR, Reden.ONBEREIKBAAR);
                case TECHNISCH_MISLUKT -> new Overgang(NotificatieStatus.TECHNISCH_MISLUKT, Reden.TECHNISCH);
                case GEPLAND, VERZONDEN, ONBEKEND -> throw new IllegalArgumentException("Geen receiptuitkomst: " + uitkomst);
            };
        }
    }
}
