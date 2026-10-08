package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
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

/**
 * Vangnet voor notificaties die zijn blijven steken: elke notificatie zonder terminale status en
 * zonder open of mislukte taak krijgt de taak die bij haar status hoort, en elke dienstverlener met een
 * webhook zonder terugkoppeltaak krijgt er een. De controletaak is een taak per systeem die zichzelf
 * na elke ronde opnieuw plant; migratie V9 zet de eerste rij.
 */
@ApplicationScoped
public class ControleTaakHandler implements TaakHandler {

    static final List<NotificatieStatus> TERMINAAL = Arrays.stream(NotificatieStatus.values())
            .filter(NotificatieStatus::isTerminaal)
            .toList();

    private final NotificatieRepository notificatieRepository;
    private final PogingRepository pogingRepository;
    private final TaakRepository taakRepository;
    private final Vaststellingstermijn vaststellingstermijn;
    private final Duration interval;
    private final int maxPerRonde;

    public ControleTaakHandler(NotificatieRepository notificatieRepository, PogingRepository pogingRepository,
                               TaakRepository taakRepository, Vaststellingstermijn vaststellingstermijn,
                               @ConfigProperty(name = "nmc.controle.interval") Duration interval,
                               @ConfigProperty(name = "nmc.controle.max-per-ronde") int maxPerRonde) {
        this.notificatieRepository = notificatieRepository;
        this.pogingRepository = pogingRepository;
        this.taakRepository = taakRepository;
        this.vaststellingstermijn = vaststellingstermijn;
        this.interval = interval;
        this.maxPerRonde = maxPerRonde;
    }

    @Override
    public boolean periodiek() {
        return true;
    }

    @Override
    public TaakSoort soort() {
        return TaakSoort.CONTROLE;
    }

    @Override
    public TaakUitkomst voerUit(Taak taak, Lease lease) {
        int gepland = QuarkusTransaction.requiringNew().call(this::herstelOntbrekendeTaken);

        if (gepland > 0) {
            Log.warnf("Controletaak: %d notificatie(s) zonder taak, ontbrekende taak gepland", gepland);
        }

        int terugkoppeltaken = QuarkusTransaction.requiringNew()
                .call(() -> taakRepository.planTerugkoppeltaken(OffsetDateTime.now(ZoneOffset.UTC)));

        if (terugkoppeltaken > 0) {
            Log.infof("Controletaak: terugkoppeltaak gepland voor %d dienstverlener(s) met een webhook", terugkoppeltaken);
        }

        return TaakUitkomst.uitgesteld(OffsetDateTime.now(ZoneOffset.UTC).plus(interval), false);
    }

    private int herstelOntbrekendeTaken() {
        List<Notificatie> zonderTaak = notificatieRepository.zonderTaak(TERMINAAL, maxPerRonde);
        OffsetDateTime nu = OffsetDateTime.now(ZoneOffset.UTC);

        int gepland = 0;

        for (Notificatie notificatie : zonderTaak) {
            Optional<TaakSoort> soort = ontbrekendeSoort(notificatie);

            if (soort.isEmpty()) {
                Log.errorf("Controletaak: notificatie %s staat op %s zonder lopende of herverzendbare poging; "
                        + "geen taak gepland",
                        notificatie.getId(), notificatie.getStatus());
                continue;
            }

            Optional<Poging> laatste = pogingRepository.findLaatsteVan(notificatie.getId());
            taakRepository.persist(new Taak(soort.get(), notificatie.getDvId(), notificatie.getId(),
                    due(soort.get(), laatste, nu), null, soort.get() == TaakSoort.VERZENDEN ? null : pogingPayload(laatste)));
            gepland++;
        }

        return gepland;
    }

    // Aangenomen of in-verzending: verzenden (een herclaim hergebruikt de poging). Verzonden met een
    // lopende poging: navraag; met een geplande of herverzendbare poging: verzenden. Bezorgd: vaststellen.
    // Verzonden zonder een van die pogingen kan niets meer doen; daarvoor wordt niets gepland, zodat de
    // controletaak niet elke ronde een taak aanmaakt die direct afrondt.
    private Optional<TaakSoort> ontbrekendeSoort(Notificatie notificatie) {
        return switch (notificatie.getStatus()) {
            case AANGENOMEN, IN_VERZENDING -> Optional.of(TaakSoort.VERZENDEN);
            case VERZONDEN -> pogingRepository.findLaatsteVan(notificatie.getId()).flatMap(ControleTaakHandler::soortBijPoging);
            case BEZORGD -> Optional.of(TaakSoort.BEZORGING_VASTSTELLEN);
            default -> throw new IllegalStateException("Terminale status " + notificatie.getStatus() + " hoort hier niet");
        };
    }

    private static Optional<TaakSoort> soortBijPoging(Poging poging) {
        if (isLopend(poging)) {
            return Optional.of(TaakSoort.RECONCILIEREN);
        }

        return poging.getStatus() == PogingStatus.GEPLAND || VerzendTaakHandler.magHerverzonden(poging)
                ? Optional.of(TaakSoort.VERZENDEN)
                : Optional.empty();
    }

    private static boolean isLopend(Poging poging) {
        return poging.getNotifyId() != null
                && (poging.getStatus() == PogingStatus.VERZONDEN || poging.getStatus() == PogingStatus.ONBEKEND);
    }

    // De vaststeltaak houdt de termijn vanaf de bezorging aan, ook als hij hier opnieuw wordt gepland;
    // zonder bekend bezorgtijdstip loopt de termijn vanaf nu, zodat hij nooit te vroeg vaststelt.
    private OffsetDateTime due(TaakSoort soort, Optional<Poging> laatste, OffsetDateTime nu) {
        return soort == TaakSoort.BEZORGING_VASTSTELLEN
                ? vaststellingstermijn.vaststellenOp(laatste.map(Poging::getBezorgdOp).orElse(nu))
                : nu;
    }

    private static Map<String, String> pogingPayload(Optional<Poging> laatste) {
        return laatste.map(p -> Map.of(VerzendTaakHandler.PAYLOAD_POGING_ID, p.getId().toString())).orElse(Map.of());
    }
}
