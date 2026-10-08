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
    private final Verzendbeleid verzendbeleid;

    public ReceiptVerwerker(PogingRepository pogingRepository, Overgangsfunctie overgangsfunctie,
                            TaakRepository taakRepository, Vaststellingstermijn vaststellingstermijn,
                            Verzendbeleid verzendbeleid) {
        this.pogingRepository = pogingRepository;
        this.overgangsfunctie = overgangsfunctie;
        this.taakRepository = taakRepository;
        this.vaststellingstermijn = vaststellingstermijn;
        this.verzendbeleid = verzendbeleid;
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

        // Op een bezorgde notificatie geeft alleen een tijdelijke of permanente fout een overgang, en alleen
        // als hij gaat over de poging waarop bezorgd rust en binnen de vaststellingstermijn valt.
        if (notificatie.getStatus() == NotificatieStatus.BEZORGD && uitkomst.get() != PogingStatus.BEZORGD
                && (uitkomst.get() == PogingStatus.TECHNISCH_MISLUKT || poging.getBezorgdOp() == null
                    || !vaststellingstermijn.binnen(poging.getBezorgdOp(), tijdstip))) {
            Log.infof("Receipt %s voor bezorgde notificatie %s geeft geen overgang (technische fout, andere poging of "
                    + "na de vaststellingstermijn); alleen op de poging vastgelegd", status, notificatie.getId());

            return;
        }

        if (uitkomst.get() != PogingStatus.BEZORGD && !isLaatstePoging(poging)) {
            Log.infof("Receipt %s voor poging %d van notificatie %s, die niet meer de laatste is; alleen op de poging "
                    + "vastgelegd", status, poging.getNummer(), notificatie.getId());

            return;
        }

        // Alleen een bezorging of een permanente fout mag bezorgstatus-onbekend nog corrigeren.
        if (notificatie.getStatus() == NotificatieStatus.BEZORGSTATUS_ONBEKEND
                && (uitkomst.get() == PogingStatus.TIJDELIJK_MISLUKT || uitkomst.get() == PogingStatus.TECHNISCH_MISLUKT)) {
            Log.infof("Receipt %s voor notificatie %s op bezorgstatus-onbekend; alleen op de poging vastgelegd",
                    status, notificatie.getId());

            return;
        }

        OffsetDateTime nu = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        Overgang overgang = Overgang.bij(uitkomst.get(), poging.getNummer() == 1, notificatie.isVerlopen(nu));

        if (overgang.herverzending()) {
            // Een eerdere faalreceipt op deze poging heeft de herverzending al gepland.
            if (vorige != PogingStatus.TIJDELIJK_MISLUKT && vorige != PogingStatus.TECHNISCH_MISLUKT) {
                planHerverzending(notificatie, nu);
            }

            return;
        }

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

    // De herverzendtaak maakt bij de claim de tweede poging aan. Vanuit bezorgd (een faalreceipt binnen de
    // vaststellingstermijn) gaat de notificatie terug naar verzonden en vervalt de vaststeltaak.
    private void planHerverzending(Notificatie notificatie, OffsetDateTime nu) {
        if (notificatie.getStatus() != NotificatieStatus.VERZONDEN && notificatie.getStatus() != NotificatieStatus.BEZORGD) {
            Log.infof("Notificatie %s staat op %s; geen herverzending, de receipt is op de poging vastgelegd",
                    notificatie.getId(), notificatie.getStatus());

            return;
        }

        taakRepository.verwijderOpen(TaakSoort.BEZORGING_VASTSTELLEN, notificatie.getId());

        if (notificatie.getStatus() == NotificatieStatus.BEZORGD) {
            overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.VERZONDEN, null);
        }

        taakRepository.persist(new Taak(TaakSoort.VERZENDEN, notificatie.getDvId(), notificatie.getId(),
                nu.plus(verzendbeleid.herverzendWachttijd(notificatie.getBerichtType())), null, null));
    }

    private boolean isLaatstePoging(Poging poging) {
        return pogingRepository.findLaatsteVan(poging.getNotificatieId()).map(p -> p.getId().equals(poging.getId())).orElse(true);
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

    // Een notificatie krijgt één herverzending, ongeacht de faalsoort: een tijdelijke of technische fout op
    // de eerste poging plant die, of geeft verlopen na geldig_tot. Op de tweede poging is elke fout terminaal.
    private record Overgang(NotificatieStatus naar, Reden reden, boolean herverzending) {

        static Overgang bij(PogingStatus uitkomst, boolean eerstePoging, boolean verlopen) {
            return switch (uitkomst) {
                case BEZORGD -> new Overgang(NotificatieStatus.BEZORGD, null, false);
                case PERMANENT_MISLUKT -> new Overgang(NotificatieStatus.NIET_BEZORGBAAR, Reden.ONBEREIKBAAR, false);
                case TIJDELIJK_MISLUKT -> eerstePoging
                        ? herverzendingOfVerlopen(verlopen)
                        : new Overgang(NotificatieStatus.NIET_BEZORGBAAR, Reden.ONBEREIKBAAR, false);
                case TECHNISCH_MISLUKT -> eerstePoging
                        ? herverzendingOfVerlopen(verlopen)
                        : new Overgang(NotificatieStatus.TECHNISCH_MISLUKT, Reden.TECHNISCH, false);
                case GEPLAND, VERZONDEN, ONBEKEND -> throw new IllegalArgumentException("Geen receiptuitkomst: " + uitkomst);
            };
        }

        private static Overgang herverzendingOfVerlopen(boolean verlopen) {
            return verlopen
                    ? new Overgang(NotificatieStatus.VERLOPEN, Reden.VERLOPEN, false)
                    : new Overgang(NotificatieStatus.VERZONDEN, null, true);
        }
    }
}
