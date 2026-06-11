package nl.rijksoverheid.moz.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.common.NotificatieStatus;
import nl.rijksoverheid.moz.common.ProfielType;
import nl.rijksoverheid.moz.common.VerzendKanaal;
import nl.rijksoverheid.moz.common.VerzendingType;
import nl.rijksoverheid.moz.dto.request.ContactherstelAanvraagRequest;
import nl.rijksoverheid.moz.dto.request.DeliveryReceiptRequest;
import nl.rijksoverheid.moz.dto.request.NotificatieAanvraagRequest;
import nl.rijksoverheid.moz.entity.Notificatie;
import nl.rijksoverheid.moz.entity.StatusGebeurtenis;
import nl.rijksoverheid.moz.entity.Verzending;
import nl.rijksoverheid.moz.mapper.NotificatieMapper;
import nl.rijksoverheid.moz.repository.NotificatieRepository;
import nl.rijksoverheid.moz.repository.VerzendingRepository;

import java.util.UUID;

@ApplicationScoped
public class NotificatieService {

    private final NotificatieRepository notificatieRepository;
    private final VerzendingRepository verzendingRepository;
    private final NotificatieMapper notificatieMapper;

    public NotificatieService(NotificatieRepository notificatieRepository,
                               VerzendingRepository verzendingRepository,
                               NotificatieMapper notificatieMapper) {
        this.notificatieRepository = notificatieRepository;
        this.verzendingRepository = verzendingRepository;
        this.notificatieMapper = notificatieMapper;
    }

    @Transactional
    public Notificatie aanmaken(NotificatieAanvraagRequest request) {
        valideerAanvraag(request);

        Notificatie notificatie = new Notificatie();
        notificatie.setProfielType(request.profielType);
        notificatie.setIdentificatieType(request.identificatieType);
        notificatie.setIdentificatieNummer(request.identificatieNummer);
        notificatie.setDienstverlener(request.dienstverlener);
        notificatie.setDienst(request.dienst);
        notificatie.setBerichtType(request.berichtType);
        notificatie.setBerichtgegevens(notificatieMapper.serialiseer(request.berichtgegevens));
        notificatie.setReferentie(request.referentie);

        Verzending verzending = new Verzending();
        verzending.setType(VerzendingType.PRIMAIR);
        verzending.setStatus(NotificatieStatus.CREATED);
        if (request.profielType == ProfielType.DECENTRAAL) {
            verzending.setKanaal(request.verzendKanaal);
            verzending.setOntvangerEmail(request.ontvangerEmail);
            verzending.setOntvangerAdres(notificatieMapper.naarAdres(request.ontvangerAdres));
        }
        // Bij CENTRAAL: kanaal en ontvangergegevens worden later bepaald via Profielservice (niet in deze skeleton)

        notificatie.addVerzending(verzending);
        notificatieRepository.persist(notificatie);

        return notificatie;
    }

    public Notificatie ophalen(UUID id) {
        return notificatieRepository.findByIdOptional(id)
                .orElseThrow(() -> new WebApplicationException("Notificatie " + id + " niet gevonden", Response.Status.NOT_FOUND));
    }

    @Transactional
    public Verzending contactherstelInitieren(UUID notificatieId, ContactherstelAanvraagRequest request) {
        valideerContactherstel(request);

        Notificatie notificatie = notificatieRepository.findByIdOptional(notificatieId)
                .orElseThrow(() -> new WebApplicationException("Notificatie " + notificatieId + " niet gevonden", Response.Status.NOT_FOUND));

        Verzending verzending = new Verzending();
        verzending.setType(VerzendingType.CONTACTHERSTEL);
        verzending.setStatus(NotificatieStatus.CREATED);
        verzending.setKanaal(request.kanaal);
        verzending.setOntvangerEmail(request.ontvangerEmail);
        verzending.setOntvangerAdres(notificatieMapper.naarAdres(request.ontvangerAdres));

        notificatie.addVerzending(verzending);

        return verzending;
    }

    @Transactional
    public Verzending verwerkDeliveryReceipt(DeliveryReceiptRequest request) {
        Verzending verzending = verzendingRepository.findByNotifyReferentie(request.id)
                .orElseThrow(() -> new WebApplicationException(
                        "Geen verzending gevonden voor Notify-referentie " + request.id, Response.Status.NOT_FOUND));

        NotificatieStatus status = notificatieMapper.naarNotificatieStatus(request.status);
        verzending.setStatus(status);
        if (request.sentAt != null) {
            verzending.setVerzondenAt(request.sentAt);
        }
        if (request.completedAt != null) {
            verzending.setAfgerondAt(request.completedAt);
        }

        StatusGebeurtenis gebeurtenis = new StatusGebeurtenis();
        gebeurtenis.setStatus(status);
        gebeurtenis.setOmschrijving(request.status);
        verzending.addStatusGebeurtenis(gebeurtenis);

        return verzending;
    }

    private void valideerAanvraag(NotificatieAanvraagRequest request) {
        if (request.profielType != ProfielType.DECENTRAAL) {
            return;
        }
        if (request.verzendKanaal == null) {
            throw new WebApplicationException("verzendKanaal is verplicht bij profielType=DECENTRAAL", Response.Status.BAD_REQUEST);
        }
        if (request.verzendKanaal == VerzendKanaal.EMAIL && request.ontvangerEmail == null) {
            throw new WebApplicationException("ontvangerEmail is verplicht bij verzendKanaal=EMAIL", Response.Status.BAD_REQUEST);
        }
        if (request.verzendKanaal == VerzendKanaal.FYSIEK && request.ontvangerAdres == null) {
            throw new WebApplicationException("ontvangerAdres is verplicht bij verzendKanaal=FYSIEK", Response.Status.BAD_REQUEST);
        }
    }

    private void valideerContactherstel(ContactherstelAanvraagRequest request) {
        if (request.kanaal == VerzendKanaal.EMAIL && request.ontvangerEmail == null) {
            throw new WebApplicationException("ontvangerEmail is verplicht bij kanaal=EMAIL", Response.Status.BAD_REQUEST);
        }
        if (request.kanaal == VerzendKanaal.FYSIEK && request.ontvangerAdres == null) {
            throw new WebApplicationException("ontvangerAdres is verplicht bij kanaal=FYSIEK", Response.Status.BAD_REQUEST);
        }
    }
}
