package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import nl.rijksoverheid.moz.nmc.notifycallback.api.model.AfleverstatusRequest;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieAanvraagRequest;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieResponse;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.ConsumentCallbackAdapter;
import nl.rijksoverheid.moz.nmc.client.notify.VerzendAdapter;
import nl.rijksoverheid.moz.nmc.client.profielservice.PartijIdentificatie;
import nl.rijksoverheid.moz.nmc.client.profielservice.ProfielServiceAdapter;
import nl.rijksoverheid.moz.nmc.common.NotificatieStatusEnum;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@ApplicationScoped
public class NotificatieService {

    private final ProfielServiceAdapter profielServiceAdapter;
    private final VerzendAdapter verzendAdapter;
    private final NotificatieRepository notificatieRepository;
    private final ConsumentCallbackAdapter consumentCallbackAdapter;

    public NotificatieService(ProfielServiceAdapter profielServiceAdapter,
                               VerzendAdapter verzendAdapter,
                               NotificatieRepository notificatieRepository,
                               ConsumentCallbackAdapter consumentCallbackAdapter) {
        this.profielServiceAdapter = profielServiceAdapter;
        this.verzendAdapter = verzendAdapter;
        this.notificatieRepository = notificatieRepository;
        this.consumentCallbackAdapter = consumentCallbackAdapter;
    }

    @Transactional
    public NotificatieResponse versturen(NotificatieAanvraagRequest request) {
        String emailAdres = profielServiceAdapter.zoekEmailAdres(new PartijIdentificatie(
                request.getIdentificatieType(), request.getIdentificatieNummer(),
                request.getDienstverlener(), request.getDienst()));

        // Persist before calling NotifyNL so that a DB failure never results in a sent email with no record.
        Notificatie notificatie = new Notificatie();
        // TODO (security): callbackUrl is unvalidated caller input that we later POST to — SSRF risk.
        notificatie.callbackUrl = request.getCallbackUrl() != null ? request.getCallbackUrl().toString() : null;
        notificatie.status = NotificatieStatusEnum.CREATED;
        notificatie.aangemaakt = OffsetDateTime.now(ZoneOffset.UTC);
        notificatieRepository.persist(notificatie);

        notificatie.notifyNlNotificatieId = verzendAdapter.verstuurEmail(emailAdres, request.getBerichtgegevens());
        notificatie.status = NotificatieStatusEnum.SENDING;

        return new NotificatieResponse().notificatieId(notificatie.id);
    }

    @Transactional
    public void verwerkAfleverstatus(AfleverstatusRequest request) {
        UUID notifyNlNotificatieId = request.getId();

        Notificatie notificatie = notificatieRepository
                .findByNotifyNlNotificatieId(notifyNlNotificatieId)
                .orElseThrow(() -> Problems.notFound("Notificatie niet gevonden",
                        "Geen notificatie gevonden voor NotifyNL-referentie " + notifyNlNotificatieId));

        notificatie.status = parseStatus(request.getStatus());

        // Een JTA-timeout tijdens de retries in stuurStatusUpdate() zou notificatie detached maken,
        // waardoor delete() hieronder een DetachedObjectException geeft. Zie ConsumentCallbackAdapter.
        if (consumentCallbackAdapter.stuurStatusUpdate(notificatie)) {
            notificatieRepository.delete(notificatie);
        }
    }

    private NotificatieStatusEnum parseStatus(String notifyStatus) {
        try {
            return NotificatieStatusEnum.valueOf(notifyStatus.replace("-", "_").toUpperCase());
        } catch (IllegalArgumentException e) {
            Log.warnf("Onbekende NotifyNL-status ontvangen: %s — opgeslagen als technische fout", notifyStatus);
            return NotificatieStatusEnum.TECHNICAL_FAILURE;
        }
    }
}
