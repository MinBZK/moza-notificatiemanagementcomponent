package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLAfleverstatus;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
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
    private final TaakRepository taakRepository;
    private final Vaststellingstermijn vaststellingstermijn;
    private final Duration klokmarge;

    public ReceiptVerwerker(PogingRepository pogingRepository, Overgangsfunctie overgangsfunctie,
                            TaakRepository taakRepository, Vaststellingstermijn vaststellingstermijn,
                            @ConfigProperty(name = "nmc.receipt.klokmarge") Duration klokmarge) {
        if (klokmarge.isNegative()) {
            throw new IllegalStateException("nmc.receipt.klokmarge mag niet negatief zijn, is " + klokmarge);
        }

        this.pogingRepository = pogingRepository;
        this.overgangsfunctie = overgangsfunctie;
        this.taakRepository = taakRepository;
        this.vaststellingstermijn = vaststellingstermijn;
        this.klokmarge = klokmarge;
    }

    /**
     * @param reference  de eigen referentie uit de receipt: het poging-id dat bij het versturen is
     *                   meegegeven; null of onbruikbaar voor verzendingen van vóór die afspraak, dan
     *                   geldt het NotifyNL-id
     * @param opgetreden het tijdstip van de status volgens NotifyNL, dat de volgorde van de receipts
     *        bepaalt, begrensd op de eigen klok plus de klokmarge; null als de receipt er geen heeft,
     *        dan geldt het moment van ontvangst en gaat de receipt nooit vóór een al vastgelegde uitkomst
     * @throws NotificatieNietGevondenException als geen poging bij deze receipt hoort
     */
    @Transactional
    public void verwerk(UUID notifyId, String reference, String status, OffsetDateTime opgetreden) {
        Poging poging = zoekPoging(notifyId, reference);
        Optional<PogingStatus> uitkomst = NotifyNLAfleverstatus.naarUitkomst(status, notifyId);

        // Een tussenstatus voor een poging die haar id al heeft, verandert niets. Een onbekende status
        // komt wel op de poging, zodat te zien blijft wat NotifyNL meldde.
        if (uitkomst.isEmpty() && poging.getNotifyId() != null && NotifyNLAfleverstatus.isTussenstatus(status)) {
            return;
        }

        // Pogingen worden pas na de rijvergrendeling gelezen, zodat twee gelijktijdige receipts
        // elkaars uitkomst zien.
        Notificatie notificatie = overgangsfunctie.vergrendel(poging.getNotificatieId());
        pogingRepository.herlaad(poging);
        poging.registreerNotifyStatus(status);
        OffsetDateTime ontvangen = normaliseer(OffsetDateTime.now(ZoneOffset.UTC));
        OffsetDateTime tijdstip = opgetreden == null ? ontvangen : begrens(normaliseer(opgetreden), ontvangen, status, notifyId);

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
        boolean verwerkt = opgetreden == null
                ? poging.verwerkReceiptZonderTijdstip(uitkomst.get(), tijdstip)
                : poging.verwerkReceipt(uitkomst.get(), tijdstip);

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

        Overgang overgang = Overgang.bepaal(uitkomst.get());
        OvergangUitkomst resultaat = overgangsfunctie.voerUit(notificatie.getId(), overgang.status(), overgang.reden());

        if (!resultaat.isUitgevoerd()) {
            Log.debugf("Notificatie %s blijft op %s; receipt %s (NotifyNL-referentie %s) is op de poging vastgelegd",
                    notificatie.getId(), resultaat.van(), status, notifyId);

            return;
        }

        // Een overgang weg van bezorgd rondt de open vaststeltaak af; een nieuwe bezorging plant een nieuwe.
        taakRepository.verwijderOpen(TaakSoort.BEZORGING_VASTSTELLEN, notificatie.getId());

        if (overgang.status() == NotificatieStatus.BEZORGD) {
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
