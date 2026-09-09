package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.ConsumentCallbackAdapter;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLConfiguratieException;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLVerzendAdapter;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLVerzendException;
import nl.rijksoverheid.moz.nmc.client.profielservice.PartijIdentificatie;
import nl.rijksoverheid.moz.nmc.client.profielservice.ProfielServiceAdapter;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;

import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class NotificatieService {

    private final ProfielServiceAdapter profielServiceAdapter;
    private final NotifyNLVerzendAdapter verzendAdapter;
    private final NotificatieRepository notificatieRepository;
    private final ConsumentCallbackAdapter consumentCallbackAdapter;

    public NotificatieService(ProfielServiceAdapter profielServiceAdapter,
                               NotifyNLVerzendAdapter verzendAdapter,
                               NotificatieRepository notificatieRepository,
                               ConsumentCallbackAdapter consumentCallbackAdapter) {
        this.profielServiceAdapter = profielServiceAdapter;
        this.verzendAdapter = verzendAdapter;
        this.notificatieRepository = notificatieRepository;
        this.consumentCallbackAdapter = consumentCallbackAdapter;
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

    @Transactional
    public void verwerkAfleverstatus(UUID notifyNlNotificatieId, String status) {
        Notificatie notificatie = notificatieRepository
                .findByExternalReference(notifyNlNotificatieId)
                .orElseThrow(() -> new NotificatieNietGevondenException(
                        "Geen notificatie gevonden voor NotifyNL-referentie " + notifyNlNotificatieId));

        StatusWaarde huidigeStatus = notificatie.getStatus();
        StatusWaarde nieuweStatus = parseStatus(status);
        // Een niet-definitieve status ná een definitieve is geen nieuwe uitkomst maar een dubbele of
        // laat aangekomen callback (NotifyNL herhaalt een callback bij elke niet-2xx). Zo'n update
        // registreren zou de laatst bekende uitkomst overschrijven, de Dienstverlener een
        // teruggedraaide bezorgstatus melden én — omdat de retentiejob op het laatste tijdstip in de
        // statusgeschiedenis vaart — de bewaartermijn opnieuw laten beginnen. Daarom wordt hij
        // genegeerd; de eerder vastgelegde eindstatus blijft staan.
        if (huidigeStatus.isDefinitief() && !nieuweStatus.isDefinitief()) {
            Log.warnf("Notificatie %s heeft al definitieve status %s; niet-definitieve status %s "
                    + "(NotifyNL-referentie %s) wordt genegeerd", notificatie.getId(), huidigeStatus,
                    nieuweStatus, notifyNlNotificatieId);

            return;
        }
        notificatie.registreerStatus(nieuweStatus);

        // TODO #732: stuurStatusUpdate() doet tot 3 synchrone HTTP-pogingen binnen deze transactie.
        // Een JTA-timeout hier rolt de registreerStatus()-aanroep hierboven terug: de net verwerkte
        // NotifyNL-uitkomst gaat dan verloren. Niet stil richting de aanroeper — de RollbackException
        // ontsnapt uit deze methode en NotifyNLCallbackController vangt hem niet, dus NotifyNL krijgt
        // een 5xx en biedt de callback opnieuw aan (het herhaalt bij elke niet-2xx). Het verlies is
        // dus echt, maar wordt gemeld; die 5xx is tegelijk precies wat de dubbele callback uitlokt
        // die hierboven wordt afgevangen.
        consumentCallbackAdapter.stuurStatusUpdate(notificatie, nieuweStatus);
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
