package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import nl.rijksoverheid.moz.nmc.domain.Reden;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Vangnet voor notificaties die zijn blijven steken: elke notificatie zonder eindstatus en
 * zonder open taak krijgt de taak die bij haar status hoort, of een eindstatus als geen taak haar
 * verder kan brengen. Een mislukte taak houdt dat tegen tot {@code geldig_tot}, daarna niet meer:
 * de notificatie loopt dan via de gewone taken naar een eindstatus en kan gewist worden; elke dienstverlener met een webhook zonder terugkoppeltaak krijgt er
 * een. De controletaak is een taak per systeem die zichzelf
 * na elke ronde opnieuw plant.
 */
@ApplicationScoped
public class ControleTaakHandler implements TaakHandler {

    // Een ronde bestaat uit losse korte transacties; de lease van de controletaak loopt intussen door.
    private static final int LEASE_VERLENGEN_NA = 100;

    private static final List<NotificatieStatus> OPEN_STATUSSEN = Arrays.stream(NotificatieStatus.values())
            .filter(status -> !status.isEindstatus())
            .toList();

    private final NotificatieRepository notificatieRepository;
    private final PogingRepository pogingRepository;
    private final TaakRepository taakRepository;
    private final Overgangsfunctie overgangsfunctie;
    private final Vaststellingstermijn vaststellingstermijn;
    private final Verzendbeleid verzendbeleid;
    private final Duration interval;
    private final int maxPerRonde;

    public ControleTaakHandler(NotificatieRepository notificatieRepository, PogingRepository pogingRepository,
                               TaakRepository taakRepository, Overgangsfunctie overgangsfunctie,
                               Vaststellingstermijn vaststellingstermijn, Verzendbeleid verzendbeleid,
                               @ConfigProperty(name = "nmc.controle.interval") Duration interval,
                               @ConfigProperty(name = "nmc.controle.max-per-ronde") int maxPerRonde) {
        this.notificatieRepository = notificatieRepository;
        this.pogingRepository = pogingRepository;
        this.taakRepository = taakRepository;
        this.overgangsfunctie = overgangsfunctie;
        this.vaststellingstermijn = vaststellingstermijn;
        this.verzendbeleid = verzendbeleid;
        this.interval = interval;
        this.maxPerRonde = maxPerRonde;
    }

    @Override
    public boolean periodiek() {
        return true;
    }

    @Override
    public TaakSoort taakSoort() {
        return TaakSoort.CONTROLE;
    }

    @Override
    public TaakUitkomst voerUit(Taak taak, Lease lease) {
        int gepland = herstelOntbrekendeTaken(lease);

        if (gepland > 0) {
            Log.warnf("Controletaak: %d notificatie(s) zonder taak, ontbrekende taak gepland", gepland);
        }

        int terugkoppeltaken = QuarkusTransaction.requiringNew()
                .call(() -> taakRepository.planTerugkoppeltaken(OffsetDateTime.now(ZoneOffset.UTC)));

        if (terugkoppeltaken > 0) {
            Log.infof("Controletaak: terugkoppeltaak gepland voor %d dienstverlener(s) met een webhook", terugkoppeltaken);
        }

        return TaakUitkomst.herpland(OffsetDateTime.now(ZoneOffset.UTC).plus(interval));
    }

    // De selectie vergrendelt niets en elke notificatie krijgt daarna haar eigen transactie: een ronde
    // houdt zo nooit meer dan één notificatierij vast, en een fout of time-out bij de ene notificatie
    // rolt alleen die ene terug.
    private int herstelOntbrekendeTaken(Lease lease) {
        OffsetDateTime nu = OffsetDateTime.now(ZoneOffset.UTC);
        List<UUID> zonderTaak = QuarkusTransaction.requiringNew()
                .call(() -> notificatieRepository.zonderTaak(OPEN_STATUSSEN, nu, maxPerRonde));

        int gepland = 0;
        int verwerkt = 0;

        for (UUID id : zonderTaak) {
            try {
                if (QuarkusTransaction.requiringNew().call(() -> herstel(id, nu))) {
                    gepland++;
                }
            } catch (RuntimeException e) {
                Log.errorf(e, "Controletaak: notificatie %s kon niet worden hersteld; volgende ronde opnieuw", id);
            }

            if (++verwerkt % LEASE_VERLENGEN_NA == 0) {
                lease.verleng();
            }
        }

        return gepland;
    }

    /** @return of er een taak is gepland */
    private boolean herstel(UUID id, OffsetDateTime nu) {
        // Een receipt of navraag kan sinds de selectie een taak hebben gepland of de status hebben
        // verzet. Onder de rijvergrendeling is dat afgerond en zichtbaar.
        Notificatie notificatie = overgangsfunctie.vergrendel(id);

        if (!notificatieRepository.isZonderTaak(id, OPEN_STATUSSEN, nu)) {
            return false;
        }

        Optional<Poging> laatste = pogingRepository.findLaatsteVan(notificatie.getId());

        // De navraag gaf geen uitkomst, maar de overgang bleef uit omdat de notificatie op dat moment
        // niet op verzonden stond; een nieuwe navraag rondt zich af zonder iets te doen.
        if (notificatie.getStatus() == NotificatieStatus.VERZONDEN
                && laatste.filter(p -> p.getStatus() == PogingStatus.ONBEKEND).isPresent()) {
            overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.BEZORGSTATUS_ONBEKEND, null);

            return false;
        }

        Optional<TaakSoort> soort = ontbrekendeSoort(notificatie, laatste);

        if (soort.isEmpty()) {
            // Zonder eindstatus komen wissen en opruimen nooit aan de beurt en blijft de sleutel staan.
            Log.errorf("Controletaak: notificatie %s staat op %s zonder lopende of herverzendbare poging; "
                    + "afgesloten als technisch-mislukt", notificatie.getId(), notificatie.getStatus());
            overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.TECHNISCH_MISLUKT, Reden.TECHNISCH);

            return false;
        }

        taakRepository.persist(new Taak(soort.get(), notificatie.getDvId(), notificatie.getId(),
                due(soort.get(), notificatie, laatste, nu), null, soort.get() == TaakSoort.VERZENDEN ? null : pogingPayload(laatste)));

        return true;
    }

    // Aangenomen of in-verzending: verzenden (een herclaim hergebruikt de poging). Verzonden met een
    // lopende poging: navraag; met een geplande of herverzendbare poging: verzenden. Bezorgd: vaststellen.
    // Verzonden zonder een van die pogingen kan niets meer doen en krijgt een eindstatus.
    private Optional<TaakSoort> ontbrekendeSoort(Notificatie notificatie, Optional<Poging> laatste) {
        return switch (notificatie.getStatus()) {
            case AANGENOMEN, IN_VERZENDING -> Optional.of(TaakSoort.VERZENDEN);
            case VERZONDEN -> laatste.flatMap(poging -> soortBijPoging(poging, notificatie.getBerichtType()));
            case BEZORGD -> Optional.of(TaakSoort.BEZORGING_VASTSTELLEN);
            default -> throw new IllegalStateException("Eindstatus " + notificatie.getStatus() + " hoort hier niet");
        };
    }

    private Optional<TaakSoort> soortBijPoging(Poging poging, String berichtType) {
        if (isLopend(poging)) {
            return Optional.of(TaakSoort.RECONCILIEREN);
        }

        return poging.getStatus() == PogingStatus.GEPLAND || verzendbeleid.magHerverzenden(poging, berichtType)
                ? Optional.of(TaakSoort.VERZENDEN)
                : Optional.empty();
    }

    private static boolean isLopend(Poging poging) {
        return poging.getNotifyId() != null && poging.getStatus() == PogingStatus.VERZONDEN;
    }

    // De vaststeltaak houdt de termijn vanaf de bezorging aan, ook als hij hier opnieuw wordt gepland;
    // zonder bekend bezorgtijdstip loopt de termijn vanaf nu, zodat hij nooit te vroeg vaststelt. Een
    // herverzending wacht de herverzend-wachttijd vanaf de fout af.
    private OffsetDateTime due(TaakSoort soort, Notificatie notificatie, Optional<Poging> laatste, OffsetDateTime nu) {
        if (soort == TaakSoort.BEZORGING_VASTSTELLEN) {
            return vaststellingstermijn.vaststellenOp(laatste.map(Poging::getBezorgdOp).orElse(nu));
        }

        Optional<OffsetDateTime> herverzendenOp = laatste
                .filter(p -> verzendbeleid.magHerverzenden(p, notificatie.getBerichtType()))
                .map(p -> Optional.ofNullable(p.getReceiptTijdstip()).orElse(nu)
                        .plus(verzendbeleid.herverzendWachttijd(notificatie.getBerichtType())));

        return herverzendenOp.filter(nu::isBefore).orElse(nu);
    }

    private static Map<String, String> pogingPayload(Optional<Poging> laatste) {
        return laatste.map(p -> Map.of(VerzendTaakHandler.PAYLOAD_POGING_ID, p.getId().toString())).orElse(Map.of());
    }
}
