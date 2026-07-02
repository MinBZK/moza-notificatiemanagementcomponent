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
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;

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

        // Persist (en flush) vóór de NotifyNL-aanroep, zodat een INSERT-fout (constraint, DB down,
        // pool uitgeput) opduikt vóórdat de e-mail verstuurd is. Let op: flush is geen commit — dit
        // dekt alleen faal vóór het versturen. Faalt de commit ná verstuurEmail(), dan rolt ook deze
        // INSERT terug: e-mail verstuurd, geen record. Dat venster sluiten (record in aparte transactie)
        // hoort bij TODO #732.
        Notificatie notificatie = new Notificatie(opdracht.callbackUrl());
        notificatieRepository.persist(notificatie);
        notificatieRepository.flush();

        try {
            notificatie.setNotifyNlNotificatieId(verzendAdapter.verstuurEmail(emailAdres, opdracht.berichtgegevens()));
        } catch (NotifyNLConfiguratieException | NotifyNLVerzendException e) {
            Log.error("Fout bij versturen van notificatie", e);
            throw new NotificatieException("Notificatie kon niet worden verstuurd.");
        }
        notificatie.setStatus(NotificatieStatus.SENDING);

        return notificatie;
    }

    @Transactional
    public void verwerkAfleverstatus(UUID notifyNlNotificatieId, String status) {
        Notificatie notificatie = notificatieRepository
                .findByNotifyNlNotificatieId(notifyNlNotificatieId)
                .orElseThrow(() -> new NotificatieNietGevondenException(
                        "Geen notificatie gevonden voor NotifyNL-referentie " + notifyNlNotificatieId));

        notificatie.setStatus(parseStatus(status));

        // Een JTA-timeout tijdens de retries in stuurStatusUpdate() zou notificatie detached maken,
        // waardoor deleteById() hieronder een DetachedObjectException geeft. Zie ConsumentCallbackAdapter.
        if (consumentCallbackAdapter.stuurStatusUpdate(notificatie)) {
            notificatieRepository.deleteById(notificatie.getId());
        }
    }

    private NotificatieStatus parseStatus(String notifyStatus) {
        try {
            return NotificatieStatus.valueOf(notifyStatus.replace("-", "_").toUpperCase());
        } catch (IllegalArgumentException e) {
            Log.errorf("Onbekende NotifyNL-status ontvangen: %s — opgeslagen als technische fout", notifyStatus);
            return NotificatieStatus.TECHNICAL_FAILURE;
        }
    }
}
