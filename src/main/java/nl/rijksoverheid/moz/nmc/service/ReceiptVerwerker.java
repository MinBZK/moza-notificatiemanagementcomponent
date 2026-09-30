package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.OvergangUitkomst;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import nl.rijksoverheid.moz.nmc.domain.Reden;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
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
    private final TaakRepository taakRepository;
    private final Vaststellingstermijn vaststellingstermijn;

    public ReceiptVerwerker(PogingRepository pogingRepository, Overgangsfunctie overgangsfunctie,
                            TaakRepository taakRepository, Vaststellingstermijn vaststellingstermijn) {
        this.pogingRepository = pogingRepository;
        this.overgangsfunctie = overgangsfunctie;
        this.taakRepository = taakRepository;
        this.vaststellingstermijn = vaststellingstermijn;
    }

    /**
     * @param reference  de eigen referentie uit de receipt: het poging-id dat bij het versturen is
     *                   meegegeven; null of onbruikbaar voor verzendingen van vóór die afspraak, dan
     *                   geldt het NotifyNL-id
     * @param opgetreden het tijdstip van de status volgens NotifyNL; bij null of een tijdstip in de
     *        toekomst geldt de eigen klok
     * @throws NotificatieNietGevondenException als geen poging bij deze receipt hoort
     */
    @Transactional
    public void verwerk(UUID notifyId, String reference, String status, OffsetDateTime opgetreden) {
        Poging poging = zoekPoging(notifyId, reference);
        Optional<PogingStatus> uitkomst = parseStatus(status, notifyId);

        // Een tussenstatus voor een poging die haar id al heeft, verandert niets.
        if (uitkomst.isEmpty() && poging.getNotifyId() != null) {
            return;
        }

        // Pogingen worden pas na de rijvergrendeling gelezen, zodat twee gelijktijdige receipts
        // elkaars uitkomst zien.
        Notificatie notificatie = overgangsfunctie.vergrendel(poging.getNotificatieId());
        pogingRepository.herlaad(poging);
        OffsetDateTime tijdstip = begrens(opgetreden);

        if (poging.getNotifyId() == null) {
            // De receipt is er eerder dan de verzend-commit van de worker: de poging neemt het id over
            // en de overgang naar verzonden gebeurt hier, namens de worker.
            poging.markeerVerzonden(notifyId, tijdstip);

            if (notificatie.getStatus() == NotificatieStatus.IN_VERZENDING) {
                overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.VERZONDEN, null);
            }
        } else if (!poging.hoortBij(notifyId)) {
            // Een tweede verzending na een herclaim: zelfde ontvanger en inhoud, dus dezelfde poging.
            poging.registreerDuplicaat(notifyId);
        }

        if (uitkomst.isEmpty()) {
            return;
        }

        PogingStatus vorige = poging.getStatus();

        if (!poging.verwerkReceipt(uitkomst.get(), tijdstip)) {
            Log.debugf("Receipt %s voor NotifyNL-referentie %s is een herhaling of ouder dan de vastgelegde "
                    + "uitkomst en wordt genegeerd", status, notifyId);

            return;
        }

        // Met een uitkomst voor de lopende poging is de navraag klaar.
        if (vorige == PogingStatus.VERZONDEN || vorige == PogingStatus.GEPLAND) {
            taakRepository.verwijderOpen(TaakSoort.RECONCILIEREN, notificatie.getId());
        }

        // Op een bezorgde notificatie geeft een faalreceipt alleen een overgang als hij gaat over de
        // poging waarop bezorgd rust en binnen de vaststellingstermijn valt.
        if (notificatie.getStatus() == NotificatieStatus.BEZORGD && uitkomst.get() != PogingStatus.BEZORGD
                && (poging.getBezorgdOp() == null || !vaststellingstermijn.binnen(poging.getBezorgdOp(), tijdstip))) {
            Log.infof("Receipt %s voor notificatie %s valt buiten de vaststellingstermijn van de bezorging; alleen op "
                    + "de poging vastgelegd", status, notificatie.getId());

            return;
        }

        Overgang overgang = Overgang.bij(uitkomst.get());
        OvergangUitkomst resultaat = overgangsfunctie.voerUit(notificatie.getId(), overgang.naar(), overgang.reden());

        if (!resultaat.isUitgevoerd()) {
            Log.debugf("Notificatie %s blijft op %s; receipt %s (NotifyNL-referentie %s) is op de poging vastgelegd",
                    notificatie.getId(), resultaat.van(), status, notifyId);

            return;
        }

        // Een overgang weg van bezorgd rondt de open vaststeltaak af; een nieuwe bezorging plant een nieuwe.
        taakRepository.verwijderOpen(TaakSoort.BEZORGING_VASTSTELLEN, notificatie.getId());

        if (overgang.naar() == NotificatieStatus.BEZORGD) {
            taakRepository.persist(new Taak(TaakSoort.BEZORGING_VASTSTELLEN, notificatie.getDvId(), notificatie.getId(),
                    vaststellingstermijn.vaststellenOp(tijdstip), null,
                    Map.of(VerzendTaakHandler.PAYLOAD_POGING_ID, poging.getId().toString())));
        }
    }

    private Poging zoekPoging(UUID notifyId, String reference) {
        return pogingRepository.zoekVoorReceipt(notifyId, reference)
                .orElseThrow(() -> new NotificatieNietGevondenException(
                        "Geen poging gevonden voor NotifyNL-referentie " + notifyId + " (reference " + reference + ")"));
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
