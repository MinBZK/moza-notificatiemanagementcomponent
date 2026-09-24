package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.transaction.Transactional;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.StatusUpdateOpdracht;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLConfiguratieException;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLVerzendAdapter;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLVerzendException;
import nl.rijksoverheid.moz.nmc.client.profielservice.PartijIdentificatie;
import nl.rijksoverheid.moz.nmc.client.profielservice.ProfielServiceAdapter;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class NotificatieService {

    private final ProfielServiceAdapter profielServiceAdapter;
    private final NotifyNLVerzendAdapter verzendAdapter;
    private final NotificatieRepository notificatieRepository;
    private final Event<StatusUpdateOpdracht> statusUpdateEvent;
    private final Sleutelbeheer sleutelbeheer;

    public NotificatieService(ProfielServiceAdapter profielServiceAdapter,
                               NotifyNLVerzendAdapter verzendAdapter,
                               NotificatieRepository notificatieRepository,
                               Event<StatusUpdateOpdracht> statusUpdateEvent,
                               Sleutelbeheer sleutelbeheer) {
        this.profielServiceAdapter = profielServiceAdapter;
        this.verzendAdapter = verzendAdapter;
        this.notificatieRepository = notificatieRepository;
        this.statusUpdateEvent = statusUpdateEvent;
        this.sleutelbeheer = sleutelbeheer;
    }

    // TODO (buiten scope): deze transactie blijft open over de synchrone Profielservice- en
    // NotifyNL-aanroepen heen en houdt zolang een DB-connectie bezet. De consument-callback draait
    // inmiddels ná de commit (StatusUpdateVerzender); deze twee aanroepen niet.
    @Transactional
    public Notificatie versturen(NotificatieVersturenOpdracht opdracht) {
        String emailAdres = profielServiceAdapter.zoekEmailAdres(new PartijIdentificatie(
                opdracht.identificatieType(), opdracht.identificatieNummer(),
                opdracht.dienstverlener(), opdracht.dienst()));

        return verstuurNaarEmail(emailAdres, opdracht.templateId(), opdracht.berichtgegevens(), opdracht.callbackUrl());
    }

    @Transactional
    public Notificatie verstuurDecentraal(DecentraleNotificatieVersturenOpdracht opdracht) {
        return verstuurNaarEmail(opdracht.emailAdres(), opdracht.templateId(), opdracht.berichtgegevens(), opdracht.callbackUrl());
    }

    private Notificatie verstuurNaarEmail(String emailAdres, String templateId, Map<String, String> berichtgegevens, String callbackUrl) {
        // Flush vóór het versturen, zodat een INSERT-fout geen verstuurde e-mail zonder record
        // oplevert. Een fout bij de commit daarna kan dat nog wel.
        Notificatie notificatie = new Notificatie(callbackUrl);
        notificatie.bewaarVersleuteldeGegevens(sleutelbeheer.versleutel(emailAdres, berichtgegevens));
        notificatieRepository.persist(notificatie);
        notificatieRepository.flush();

        try {
            notificatie.markeerVerzonden(verzendAdapter.verstuurEmail(emailAdres, templateId, berichtgegevens));
        } catch (NotifyNLConfiguratieException | NotifyNLVerzendException e) {
            Log.error("Fout bij versturen van notificatie", e);
            throw new NotificatieException("Notificatie kon niet worden verstuurd.");
        }

        return notificatie;
    }

    /**
     * @param opgetreden wanneer de status bij NotifyNL ontstond (hun completed_at/sent_at/created_at).
     *        Mag null zijn: geen van die velden is verplicht in NotifyNL's eigen callbackschema
     *        (EmailCallbackRequest in notifynl_api.yaml kent geen required), en dan valt de NMC terug
     *        op de eigen klok.
     */
    @Transactional
    public void verwerkAfleverstatus(UUID notifyNlNotificatieId, String status, OffsetDateTime opgetreden) {
        Notificatie notificatie = notificatieRepository
                .findByExternalReference(notifyNlNotificatieId)
                .orElseThrow(() -> new NotificatieNietGevondenException(
                        "Geen notificatie gevonden voor NotifyNL-referentie " + notifyNlNotificatieId));

        StatusWaarde huidigeStatus = notificatie.getStatus();
        StatusWaarde nieuweStatus = parseStatus(status, notifyNlNotificatieId, notificatie.getId());

        // NotifyNL herhaalt een callback bij elke niet-2xx, dus dezelfde receipt komt vaker binnen.
        // Een status die eerder al voorbijkwam maar niet de huidige is, wordt wél doorgegeven.
        if (!notificatie.verwerkTerugmelding(nieuweStatus, opgetreden)) {
            Log.debugf("Notificatie %s heeft al status %s; status %s (NotifyNL-referentie %s) is geen "
                    + "nieuwe melding en wordt genegeerd", notificatie.getId(), huidigeStatus,
                    nieuweStatus, notifyNlNotificatieId);

            return;
        }

        // Afvuren en niet zelf versturen: StatusUpdateVerzender pakt dit pas op ná de commit, die
        // alsnog kan falen. Zo krijgt de Dienstverlener geen status die de NMC daarna terugrolt, en
        // blijft de DB-connectie niet bezet zolang de callback duurt.
        statusUpdateEvent.fire(new StatusUpdateOpdracht(
                notificatie.getId(), notificatie.getCallbackUrl(), nieuweStatus));
    }

    // ERROR en niet WARN: NotifyNL meldt dan iets terug waar dit component geen afhandeling voor
    // heeft, en dat hoort meteen op te vallen. Met beide identificatoren en de ruwe waarde erbij,
    // want dat is wat nodig is om te bepalen of StatusWaarde uitgebreid moet worden.
    private StatusWaarde parseStatus(String notifyStatus, UUID notifyNlNotificatieId, UUID notificatieId) {
        try {
            // Locale.ROOT: in een Turkse locale maakt toUpperCase() van de i een I met punt, waardoor
            // technical-failure buiten valueOf valt en als ONBEKEND zou landen.
            return StatusWaarde.valueOf(notifyStatus.replace("-", "_").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Log.errorf("Onbekende NotifyNL-status '%s' ontvangen voor notificatie %s "
                    + "(NotifyNL-referentie %s) — vastgelegd als %s; StatusWaarde kent deze waarde "
                    + "niet, controleer of NotifyNL nieuwe statussen is gaan sturen",
                    notifyStatus, notificatieId, notifyNlNotificatieId, StatusWaarde.ONBEKEND.toApiValue());

            return StatusWaarde.ONBEKEND;
        }
    }
}
