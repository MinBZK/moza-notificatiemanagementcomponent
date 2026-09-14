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
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class NotificatieService {

    private final ProfielServiceAdapter profielServiceAdapter;
    private final NotifyNLVerzendAdapter verzendAdapter;
    private final NotificatieRepository notificatieRepository;
    private final Event<StatusUpdateOpdracht> statusUpdateEvent;

    public NotificatieService(ProfielServiceAdapter profielServiceAdapter,
                               NotifyNLVerzendAdapter verzendAdapter,
                               NotificatieRepository notificatieRepository,
                               Event<StatusUpdateOpdracht> statusUpdateEvent) {
        this.profielServiceAdapter = profielServiceAdapter;
        this.verzendAdapter = verzendAdapter;
        this.notificatieRepository = notificatieRepository;
        this.statusUpdateEvent = statusUpdateEvent;
    }

    // TODO #732 (zie https://github.com/MinBZK/MijnOverheidZakelijk/issues/732): zelfde probleem
    // als in ConsumentCallbackAdapter — deze @Transactional methode houdt een DB-connectie open
    // over de synchrone Profielservice- en NotifyNL-aanroepen heen. Onder belasting kan dit de
    // connection pool uitputten — los van de callback-retries.
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
        // Persist (en flush) vóór de NotifyNL-aanroep, zodat een INSERT-fout (constraint, DB down,
        // pool uitgeput) opduikt vóórdat de e-mail verstuurd is. Let op: flush is geen commit — dit
        // dekt alleen faal vóór het versturen. Faalt de commit ná verstuurEmail(), dan rolt ook deze
        // INSERT terug: e-mail verstuurd, geen record. Dat venster sluiten (record in aparte transactie)
        // hoort bij TODO #732.
        Notificatie notificatie = new Notificatie(callbackUrl);
        notificatieRepository.persist(notificatie);
        notificatieRepository.flush();

        try {
            notificatie.setExternalReference(verzendAdapter.verstuurEmail(emailAdres, templateId, berichtgegevens));
        } catch (NotifyNLConfiguratieException | NotifyNLVerzendException e) {
            Log.error("Fout bij versturen van notificatie", e);
            throw new NotificatieException("Notificatie kon niet worden verstuurd.");
        }
        notificatie.registreerStatus(StatusWaarde.SENDING);

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

        StatusWaarde huidigeStatus = notificatie.getStatus().status();
        StatusWaarde nieuweStatus = parseStatus(status);
        // NotifyNL herhaalt een callback bij elke niet-2xx, dus dezelfde delivery receipt kan
        // meerdere keren binnenkomen en twee receipts voor een verzending kunnen elkaar in
        // omgekeerde volgorde bereiken. Alleen een status die een vooruitgang is ten opzichte van de
        // vastgelegde status wordt geregistreerd; al het andere is een herhaling of een laat
        // aangekomen callback. Die toch registreren zou de laatst bekende uitkomst overschrijven —
        // een al bezorgde notificatie zou alsnog als mislukt bij de Dienstverlener landen — én,
        // omdat de retentiejob op het laatste tijdstip in de statusgeschiedenis vaart, de
        // bewaartermijn opnieuw laten beginnen. Zie StatusWaarde#volgtOp voor de rangorde.
        if (!nieuweStatus.volgtOp(huidigeStatus)) {
            Log.warnf("Notificatie %s heeft al status %s; status %s (NotifyNL-referentie %s) is daar "
                    + "geen vooruitgang op en wordt genegeerd", notificatie.getId(), huidigeStatus,
                    nieuweStatus, notifyNlNotificatieId);

            return;
        }
        // De gebeurtenistijd van NotifyNL, niet het moment van verwerken: NotifyNL herhaalt een
        // callback tot 5x met 5 minuten ertussen, dus die twee lopen bij een herhaling tientallen
        // minuten uiteen. Alleen deze kolom komt van een externe klok; de bewaartermijn vaart op
        // Notificatie#laatsteStatusUpdate, dat de eigen klok houdt.
        notificatie.registreerStatus(nieuweStatus,
                opgetreden != null ? opgetreden : OffsetDateTime.now(ZoneOffset.UTC));

        // Afvuren, niet zelf versturen: StatusUpdateVerzender pakt dit pas op ná de commit van deze
        // transactie. Zou de statusupdate hier direct verstuurd worden, dan kan de Dienstverlener een
        // status krijgen die de NMC vervolgens terugrolt — de commit hierna kan alsnog falen op een
        // OptimisticLockException (een gelijktijdige tweede receipt) of op een JTA-timeout. Het houdt
        // bovendien de DB-connectie van deze transactie niet bezet zolang de callback duurt.
        statusUpdateEvent.fire(new StatusUpdateOpdracht(
                notificatie.getId(), notificatie.getCallbackUrl(), nieuweStatus));
    }

    private StatusWaarde parseStatus(String notifyStatus) {
        try {
            return StatusWaarde.valueOf(notifyStatus.replace("-", "_").toUpperCase());
        } catch (IllegalArgumentException e) {
            Log.errorf("Onbekende NotifyNL-status ontvangen: %s — opgeslagen als %s", notifyStatus,
                    StatusWaarde.ONBEKEND.toApiValue());
            return StatusWaarde.ONBEKEND;
        }
    }
}
