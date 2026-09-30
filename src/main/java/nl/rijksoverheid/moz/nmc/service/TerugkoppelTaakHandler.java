package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.NotificatieStatusEvent;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.WebhookLeveringException;
import nl.rijksoverheid.moz.nmc.domain.Cursor;
import nl.rijksoverheid.moz.nmc.domain.Dienstverlener;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.domain.Webhookpositie;
import nl.rijksoverheid.moz.nmc.repository.DienstverlenerRepository;
import nl.rijksoverheid.moz.nmc.repository.WebhookpositieRepository;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * De terugkoppeltaak: één taak per dienstverlener met een webhook, die zichzelf steeds opnieuw plant.
 * Per ronde leest hij de events na de leverpositie onder het watermerk van de feed en levert ze als
 * één bundel aan de webhook. Na een 2xx gaat de leverpositie vooruit in dezelfde transactie die de
 * taak opnieuw plant, zodat een worker die zijn lease verloor de positie niet verzet. Een 2xx is
 * geen bevestiging; die schrijft de Dienstverlener zelf met de feedcursor.
 * <p>
 * Een geslaagde ronde zet de pogingen van de taak terug, zodat alleen een reeks onverwachte fouten
 * op rij hem op mislukt zet en niet fouten verspreid over zijn hele levensduur.
 * <p>
 * Een mislukte levering kost de taak geen poging: de wachttijd loopt op met het aantal mislukkingen
 * op rij, en na het afgesproken aantal pauzeert de webhook met een oplopende wachttijd tot ten
 * hoogste een dag. Een geslaagde levering zet de teller terug. Een ongeldige webhook-URL geldt als
 * mislukking die direct pauzeert, zonder aanroep.
 */
@ApplicationScoped
public class TerugkoppelTaakHandler implements TaakHandler {

    static final Duration MAX_WACHTTIJD = Duration.ofDays(1);

    private final DienstverlenerRepository dienstverlenerRepository;
    private final WebhookpositieRepository webhookpositieRepository;
    private final Eventfeed eventfeed;
    private final WebhookUrlControle webhookUrlControle;
    private final WebhookDispatcher webhookDispatcher;
    private final TaakClaimer taakClaimer;
    private final Duration interval;
    private final int bundel;
    private final int maxMislukkingen;
    private final Duration herpogingWachttijd;
    private final Duration pauzeWachttijd;

    public TerugkoppelTaakHandler(DienstverlenerRepository dienstverlenerRepository,
                                  WebhookpositieRepository webhookpositieRepository, Eventfeed eventfeed,
                                  WebhookUrlControle webhookUrlControle, WebhookDispatcher webhookDispatcher,
                                  TaakClaimer taakClaimer,
                                  @ConfigProperty(name = "nmc.webhook.interval") Duration interval,
                                  @ConfigProperty(name = "nmc.webhook.bundel") int bundel,
                                  @ConfigProperty(name = "nmc.webhook.max-mislukkingen") int maxMislukkingen,
                                  @ConfigProperty(name = "nmc.webhook.herpoging-wachttijd") Duration herpogingWachttijd,
                                  @ConfigProperty(name = "nmc.webhook.pauze-wachttijd") Duration pauzeWachttijd) {
        if (bundel < 1 || maxMislukkingen < 1) {
            throw new IllegalStateException("nmc.webhook.bundel en nmc.webhook.max-mislukkingen moeten 1 of hoger zijn");
        }

        this.dienstverlenerRepository = dienstverlenerRepository;
        this.webhookpositieRepository = webhookpositieRepository;
        this.eventfeed = eventfeed;
        this.webhookUrlControle = webhookUrlControle;
        this.webhookDispatcher = webhookDispatcher;
        this.taakClaimer = taakClaimer;
        this.interval = interval;
        this.bundel = bundel;
        this.maxMislukkingen = maxMislukkingen;
        this.herpogingWachttijd = herpogingWachttijd;
        this.pauzeWachttijd = pauzeWachttijd;
    }

    // Eén rij per dienstverlener; de controletaak maakt hem niet opnieuw aan zolang er een rij staat.
    @Override
    public boolean periodiek() {
        return true;
    }

    @Override
    public TaakSoort soort() {
        return TaakSoort.TERUGKOPPELEN;
    }

    @Override
    public TaakUitkomst voerUit(Taak taak, Lease lease) {
        UUID dvId = taak.getDvId();

        if (dvId == null) {
            Log.errorf("Terugkoppeltaak %d heeft geen dienstverlener; de taak vervalt", taak.getId());

            return TaakUitkomst.afgerond();
        }

        Ronde ronde = QuarkusTransaction.requiringNew().call(() -> bereidVoor(dvId));

        return switch (ronde) {
            case GeenWebhook g -> {
                Log.infof("Dienstverlener %s heeft geen webhook meer; de terugkoppeltaak vervalt", dvId);
                yield TaakUitkomst.afgerond();
            }
            case Gepauzeerd g -> TaakUitkomst.uitgesteld(g.tot(), false);
            case GeenEvents g -> {
                QuarkusTransaction.requiringNew().run(() -> taakClaimer.herplanNaSucces(taak, nu().plus(interval)));
                yield TaakUitkomst.alAfgerond();
            }
            case OngeldigeUrl o -> {
                Log.errorf("Webhook-URL van dienstverlener %s is ongeldig (%s); de webhook pauzeert zonder aanroep",
                        dvId, o.reden());
                QuarkusTransaction.requiringNew().run(() -> registreerMislukking(taak, o.maxMislukkingen(), true));
                yield TaakUitkomst.alAfgerond();
            }
            case Levering l -> lever(taak, lease, l);
        };
    }

    private TaakUitkomst lever(Taak taak, Lease lease, Levering levering) {
        lease.verleng();

        try {
            webhookDispatcher.lever(levering.url(), levering.events(), levering.cursor());
        } catch (WebhookLeveringException e) {
            Log.warnf(e, "Levering van %d event(s) aan de webhook van dienstverlener %s mislukt",
                    levering.events().size(), taak.getDvId());
            QuarkusTransaction.requiringNew().run(() -> registreerMislukking(taak, levering.maxMislukkingen(), false));

            return TaakUitkomst.alAfgerond();
        }

        QuarkusTransaction.requiringNew().run(() -> registreerLevering(taak, levering));

        return TaakUitkomst.alAfgerond();
    }

    private Ronde bereidVoor(UUID dvId) {
        Optional<Dienstverlener> dienstverlener = dienstverlenerRepository.findByIdOptional(dvId);
        Optional<String> webhookUrl = dienstverlener.flatMap(Dienstverlener::getWebhookUrl);

        if (webhookUrl.isEmpty()) {
            return new GeenWebhook();
        }

        int max = dienstverlener.get().getWebhookMaxMislukkingen().orElse(maxMislukkingen);
        Webhookpositie positie = webhookpositieRepository.zoek(dvId);

        if (positie.gepauzeerdTot() != null && positie.gepauzeerdTot().isAfter(nu())) {
            return new Gepauzeerd(positie.gepauzeerdTot());
        }

        String url;
        try {
            url = webhookUrlControle.controleer(webhookUrl.get());
        } catch (OngeldigeCallbackUrlException e) {
            return new OngeldigeUrl(e.getMessage(), max);
        }

        EventPagina pagina = eventfeed.leesVoorWebhook(dvId, positie.positie(), bundel);

        if (pagina.events().isEmpty()) {
            return new GeenEvents();
        }

        List<NotificatieStatusEvent> events = pagina.events().stream().map(NotificatieStatusEvent::van).toList();

        return new Levering(url, events, pagina.cursor(), max);
    }

    // Direct weer aan de beurt: er kunnen meer events klaarstaan dan in één bundel pasten.
    private void registreerLevering(Taak taak, Levering levering) {
        OffsetDateTime nu = nu();
        taakClaimer.herplanNaSucces(taak, nu);
        webhookpositieRepository.bewaar(new Webhookpositie(taak.getDvId(), levering.cursor(), 0, null), nu);
    }

    private void registreerMislukking(Taak taak, int max, boolean directPauzeren) {
        OffsetDateTime nu = nu();
        Webhookpositie positie = webhookpositieRepository.zoek(taak.getDvId());
        int mislukkingen = positie.mislukkingen() + 1;
        boolean pauzeert = directPauzeren || mislukkingen >= max;
        Duration wachttijd = pauzeert
                ? oplopend(pauzeWachttijd, Math.max(0, mislukkingen - max))
                : oplopend(herpogingWachttijd, mislukkingen - 1);
        OffsetDateTime due = nu.plus(wachttijd);

        taakClaimer.stelUit(taak, due, false);
        webhookpositieRepository.bewaar(
                new Webhookpositie(taak.getDvId(), positie.positie(), mislukkingen, pauzeert ? due : null), nu);

        if (pauzeert) {
            Log.warnf("Webhook van dienstverlener %s gepauzeerd tot %s na %d mislukte levering(en) op rij; de feed "
                    + "blijft beschikbaar", taak.getDvId(), due, mislukkingen);
        }
    }

    // Verdubbelt per stap, begrensd op een dag zodat een hoog aantal mislukkingen niet overloopt.
    static Duration oplopend(Duration basis, int stap) {
        Duration wachttijd = basis.multipliedBy(1L << Math.min(stap, 20));

        return wachttijd.compareTo(MAX_WACHTTIJD) > 0 ? MAX_WACHTTIJD : wachttijd;
    }

    private static OffsetDateTime nu() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private sealed interface Ronde {
    }

    private record GeenWebhook() implements Ronde {
    }

    private record Gepauzeerd(OffsetDateTime tot) implements Ronde {
    }

    private record GeenEvents() implements Ronde {
    }

    private record OngeldigeUrl(String reden, int maxMislukkingen) implements Ronde {
    }

    private record Levering(String url, List<NotificatieStatusEvent> events, Cursor cursor, int maxMislukkingen)
            implements Ronde {
    }
}
