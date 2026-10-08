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
import java.util.UUID;

/**
 * Neemt een notificatie aan: notificatie op {@code aangenomen} met event 0, de versleutelde payload en
 * de verzendtaak, in één transactie. Het antwoord aan de aanroeper volgt op de commit; de verzending
 * doet de worker. De aanname roept de Profielservice en NotifyNL niet aan.
 */
@ApplicationScoped
public class AannameService {

    private final Overgangsfunctie overgangsfunctie;
    private final Sleutelbeheer sleutelbeheer;
    private final DvProvider dvProvider;
    private final DienstverlenerRepository dienstverlenerRepository;
    private final NotificatieRepository notificatieRepository;
    private final TaakRepository taakRepository;
    private final Verzendbeleid verzendbeleid;

    public AannameService(Overgangsfunctie overgangsfunctie, Sleutelbeheer sleutelbeheer, DvProvider dvProvider,
                          DienstverlenerRepository dienstverlenerRepository, NotificatieRepository notificatieRepository,
                          TaakRepository taakRepository, Verzendbeleid verzendbeleid) {
        this.overgangsfunctie = overgangsfunctie;
        this.sleutelbeheer = sleutelbeheer;
        this.dvProvider = dvProvider;
        this.dienstverlenerRepository = dienstverlenerRepository;
        this.notificatieRepository = notificatieRepository;
        this.taakRepository = taakRepository;
        this.verzendbeleid = verzendbeleid;
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
        Notificatie notificatie = new Notificatie(dvId);
        notificatie.bewaarVersleuteldeGegevens(
                sleutelbeheer.versleutel(notificatie.getId(), opdracht.ontvanger(), opdracht.berichtgegevens()));
        // Op de notificatie en niet in de taak, zodat ook een later geplande verzendtaak kan versturen.
        notificatie.bewaarVerzendgegevens(opdracht.berichtType().getTemplateId(), opdracht.regie().name(),
                opdracht.dienstverlener(), opdracht.dienst());
        notificatie.bewaarBeleid(opdracht.berichtType().name(),
                OffsetDateTime.now(ZoneOffset.UTC).plus(verzendbeleid.geldigheid(opdracht.berichtType())));
        overgangsfunctie.neemAan(notificatie);

        taakRepository.persist(new Taak(TaakSoort.VERZENDEN, dvId, notificatie.getId(),
                OffsetDateTime.now(ZoneOffset.UTC), UUID.randomUUID().toString(), null));

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
