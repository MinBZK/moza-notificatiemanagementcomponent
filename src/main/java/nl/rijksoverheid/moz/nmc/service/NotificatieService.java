package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.ConsumentCallbackAdapter;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLVerzendAdapter;
import nl.rijksoverheid.moz.nmc.client.profielservice.PartijIdentificatie;
import nl.rijksoverheid.moz.nmc.client.profielservice.ProfielServiceAdapter;
import nl.rijksoverheid.moz.nmc.common.NotificatieStatusEnum;
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

        // Persist (en flush) before calling NotifyNL so that a DB failure never results in a sent email with no record.
        Notificatie notificatie = new Notificatie(opdracht.callbackUrl());
        notificatieRepository.persist(notificatie);
        notificatieRepository.flush();

        notificatie.setNotifyNlNotificatieId(verzendAdapter.verstuurEmail(emailAdres, opdracht.berichtgegevens()));
        notificatie.setStatus(NotificatieStatusEnum.SENDING);

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
        // waardoor delete() hieronder een DetachedObjectException geeft. Zie ConsumentCallbackAdapter.
        if (consumentCallbackAdapter.stuurStatusUpdate(notificatie)) {
            notificatieRepository.deleteById(notificatie.getId());
        }
    }

    private NotificatieStatusEnum parseStatus(String notifyStatus) {
        try {
            return NotificatieStatusEnum.valueOf(notifyStatus.replace("-", "_").toUpperCase());
        } catch (IllegalArgumentException e) {
            Log.errorf("Onbekende NotifyNL-status ontvangen: %s — opgeslagen als technische fout", notifyStatus);
            return NotificatieStatusEnum.TECHNICAL_FAILURE;
        }
    }
}
