package nl.rijksoverheid.moz.nmc.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import nl.rijksoverheid.moz.nmc.domain.Dienstverlener;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.DienstverlenerRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Neemt een notificatie aan: notificatie op {@code aangenomen} met event 0, de versleutelde payload en
 * de verzendtaak, in één transactie. Het antwoord aan de aanroeper volgt op de commit; de verzending
 * doet de worker. De aanname roept de Profielservice en NotifyNL niet aan.
 */
@ApplicationScoped
public class AannameService {

    static final String PAYLOAD_TEMPLATE_ID = "templateId";
    static final String PAYLOAD_REGIE = "regie";
    static final String PAYLOAD_DIENSTVERLENER = "dienstverlener";
    static final String PAYLOAD_DIENST = "dienst";

    private final Overgangsfunctie overgangsfunctie;
    private final Sleutelbeheer sleutelbeheer;
    private final DvProvider dvProvider;
    private final DienstverlenerRepository dienstverlenerRepository;
    private final NotificatieRepository notificatieRepository;
    private final TaakRepository taakRepository;

    public AannameService(Overgangsfunctie overgangsfunctie, Sleutelbeheer sleutelbeheer, DvProvider dvProvider,
                          DienstverlenerRepository dienstverlenerRepository, NotificatieRepository notificatieRepository,
                          TaakRepository taakRepository) {
        this.overgangsfunctie = overgangsfunctie;
        this.sleutelbeheer = sleutelbeheer;
        this.dvProvider = dvProvider;
        this.dienstverlenerRepository = dienstverlenerRepository;
        this.notificatieRepository = notificatieRepository;
        this.taakRepository = taakRepository;
    }

    /**
     * @return het id van de aangenomen notificatie
     * @throws QuotumOverschredenException als de dienstverlener zijn quotum voor vandaag heeft bereikt
     */
    @Transactional
    public UUID neemAan(AannameOpdracht opdracht) {
        UUID dvId = dvProvider.huidigeDvId();
        toetsQuotum(dvId);

        // De versleutelde gegevens gaan vóór de aanname op de entity, zodat de insert ze meeneemt.
        Notificatie notificatie = new Notificatie(dvId, opdracht.callbackUrl());
        notificatie.bewaarVersleuteldeGegevens(
                sleutelbeheer.versleutel(notificatie.getId(), opdracht.ontvanger(), opdracht.berichtgegevens()));
        overgangsfunctie.neemAan(notificatie);

        // Alleen verwijzingen in de payload; adres en nummer staan versleuteld op de notificatie.
        Map<String, String> payload = new HashMap<>();
        payload.put(PAYLOAD_TEMPLATE_ID, opdracht.templateId());
        payload.put(PAYLOAD_REGIE, opdracht.regie().name());

        if (opdracht.dienstverlener() != null) {
            payload.put(PAYLOAD_DIENSTVERLENER, opdracht.dienstverlener());
        }

        if (opdracht.dienst() != null) {
            payload.put(PAYLOAD_DIENST, opdracht.dienst());
        }

        taakRepository.persist(new Taak(TaakSoort.VERZENDEN, dvId, notificatie.getId(),
                OffsetDateTime.now(ZoneOffset.UTC), UUID.randomUUID().toString(), payload));

        return notificatie.getId();
    }

    // Per kalenderdag in UTC; het register bepaalt of er een grens is.
    private void toetsQuotum(UUID dvId) {
        Dienstverlener dienstverlener = dienstverlenerRepository.findById(dvId);

        if (dienstverlener == null) {
            throw new IllegalStateException("Dienstverlener " + dvId + " staat niet in het register");
        }

        dienstverlener.getQuotumPerDag().ifPresent(quotum -> {
            OffsetDateTime beginVanDeDag = LocalDate.now(ZoneOffset.UTC).atStartOfDay().atOffset(ZoneOffset.UTC);

            if (notificatieRepository.telAangenomenSinds(dvId, beginVanDeDag) >= quotum) {
                throw new QuotumOverschredenException(quotum);
            }
        });
    }
}
