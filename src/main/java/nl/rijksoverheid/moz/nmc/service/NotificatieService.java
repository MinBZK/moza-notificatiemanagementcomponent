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
import java.util.Locale;
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

    // TODO #732 (zie https://github.com/MinBZK/MijnOverheidZakelijk/issues/732): deze
    // @Transactional methode houdt een DB-connectie open over de synchrone Profielservice- en
    // NotifyNL-aanroepen heen. Onder belasting kan dat de connection pool uitputten. Voor de
    // consument-callback is dit inmiddels opgelost (die draait na de commit, zie
    // StatusUpdateVerzender); voor deze twee aanroepen niet.
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

        StatusWaarde huidigeStatus = notificatie.getStatus().status();
        StatusWaarde nieuweStatus = parseStatus(status, notifyNlNotificatieId, notificatie.getId());
        // NotifyNL herhaalt een callback bij elke niet-2xx, dus dezelfde delivery receipt kan
        // meerdere keren binnenkomen en twee receipts voor een verzending kunnen elkaar in
        // omgekeerde volgorde bereiken. Alleen een status die een vooruitgang is ten opzichte van de
        // vastgelegde status wordt geregistreerd; al het andere is een herhaling of een laat
        // aangekomen callback. Die toch registreren zou de laatst bekende uitkomst overschrijven —
        // een al bezorgde notificatie zou alsnog als mislukt bij de Dienstverlener landen — én,
        // omdat de retentiejob op het laatste tijdstip in de statusgeschiedenis vaart, de
        // bewaartermijn opnieuw laten beginnen. Zie StatusWaarde#volgtOp voor de rangorde.
        if (!nieuweStatus.volgtOp(huidigeStatus)) {
            meldGenegeerdeStatus(notificatie.getId(), huidigeStatus, nieuweStatus, notifyNlNotificatieId);

            return;
        }
        // De gebeurtenistijd van NotifyNL, niet het moment van verwerken: NotifyNL herhaalt een
        // callback tot 5x met 5 minuten ertussen, dus die twee lopen bij een herhaling tientallen
        // minuten uiteen. Alleen deze kolom komt van een externe klok; de bewaartermijn vaart op
        // de registratietijd (kolom laatste_status_update), die de eigen klok houdt.
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

    // Twee niveaus, want hier komen twee wezenlijk verschillende dingen samen.
    //
    // Het verwachte geval is een herhaling of een laat aangekomen receipt: NotifyNL herhaalt bij elke
    // niet-2xx, dus bij één trage consument-callback komen er zo vier voor dezelfde notificatie. Dat
    // op WARN loggen leert een operator WARNs negeren, en dan verdwijnt het geval hieronder in de ruis.
    //
    // Het afwijkende geval is dat NotifyNL twee verschillende eindstatussen meldt voor dezelfde
    // verzending. Dat hoort niet te kunnen: per notificatie stuurt NotifyNL één uitkomst. Gebeurt het
    // toch, dan is er iets mis bij NotifyNL of klopt de koppeling op external_reference niet, en dat
    // verdient onderzoek. Let op dat dit feit verder nergens wordt vastgelegd — de geweigerde status
    // gaat niet de geschiedenis in, dus deze regel is het enige spoor.
    private static void meldGenegeerdeStatus(UUID notificatieId, StatusWaarde huidigeStatus,
            StatusWaarde nieuweStatus, UUID notifyNlNotificatieId) {
        if (huidigeStatus.isDefinitief() && nieuweStatus.isDefinitief() && huidigeStatus != nieuweStatus) {
            Log.warnf("Notificatie %s staat op %s en NotifyNL meldt daarna %s (referentie %s): "
                    + "tegenstrijdige uitkomsten voor één verzending — de vastgelegde uitkomst blijft staan",
                    notificatieId, huidigeStatus, nieuweStatus, notifyNlNotificatieId);

            return;
        }
        Log.debugf("Notificatie %s heeft al status %s; status %s (NotifyNL-referentie %s) is daar geen "
                + "vooruitgang op en wordt genegeerd", notificatieId, huidigeStatus, nieuweStatus,
                notifyNlNotificatieId);
    }

    // ERROR en niet WARN: een status die de NMC niet kent betekent dat NotifyNL iets terugmeldt
    // waar dit component geen afhandeling voor heeft, en dat hoort meteen op te vallen. Wachten tot
    // de retentiejob de notificatie opruimt en hem dan pas als "verlopen zonder definitieve status"
    // meldt, is dagen te laat en wijst bovendien naar het verkeerde probleem.
    //
    // Met beide identificatoren erbij, anders is de melding niet te herleiden tot een notificatie om
    // te onderzoeken. De onbekende waarde staat er letterlijk in, want dat is wat je nodig hebt om te
    // bepalen of StatusWaarde uitgebreid moet worden.
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
