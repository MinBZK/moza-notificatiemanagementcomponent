package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import nl.rijksoverheid.moz.nmc.domain.Dienstverlener;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.DienstverlenerRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Neemt een notificatie aan: notificatie op {@code aangenomen} met event 0, de versleutelde payload en
 * de verzendtaak, in één transactie. Het antwoord aan de aanroeper volgt op de commit; de verzending
 * doet de worker. De aanname roept de Profielservice en NotifyNL niet aan.
 * Eager bij het opstarten, zodat een ongeldig standaardquotum de applicatie niet laat starten.
 */
@Startup
@ApplicationScoped
public class AannameService {

    private final Overgangsfunctie overgangsfunctie;
    private final Sleutelbeheer sleutelbeheer;
    private final DvProvider dvProvider;
    private final DienstverlenerRepository dienstverlenerRepository;
    private final NotificatieRepository notificatieRepository;
    private final TaakRepository taakRepository;
    private final Optional<Integer> standaardQuotumPerDag;

    public AannameService(Overgangsfunctie overgangsfunctie, Sleutelbeheer sleutelbeheer, DvProvider dvProvider,
                          DienstverlenerRepository dienstverlenerRepository, NotificatieRepository notificatieRepository,
                          TaakRepository taakRepository,
                          @ConfigProperty(name = "nmc.aanname.quotum-per-dag") Optional<Integer> standaardQuotumPerDag) {
        if (standaardQuotumPerDag.isPresent() && standaardQuotumPerDag.get() < 1) {
            throw new IllegalStateException("nmc.aanname.quotum-per-dag moet leeg of 1 of hoger zijn");
        }

        this.overgangsfunctie = overgangsfunctie;
        this.sleutelbeheer = sleutelbeheer;
        this.dvProvider = dvProvider;
        this.dienstverlenerRepository = dienstverlenerRepository;
        this.notificatieRepository = notificatieRepository;
        this.taakRepository = taakRepository;
        this.standaardQuotumPerDag = standaardQuotumPerDag;
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
        notificatie.bewaarVerzendgegevens(opdracht.templateId(), opdracht.regie().name(), opdracht.dienstverlener(),
                opdracht.dienst());
        overgangsfunctie.neemAan(notificatie);

        taakRepository.persist(new Taak(TaakSoort.VERZENDEN, dvId, notificatie.getId(),
                OffsetDateTime.now(ZoneOffset.UTC), UUID.randomUUID().toString(), null));

        return notificatie.getId();
    }

    // Per kalenderdag in UTC. Het quotum uit het register gaat voor; zonder quotum daar geldt de standaard.
    private void toetsQuotum(UUID dvId) {
        Dienstverlener dienstverlener = dienstverlenerRepository.findById(dvId);

        if (dienstverlener == null) {
            throw new IllegalStateException("Dienstverlener " + dvId + " staat niet in het register");
        }

        dienstverlener.getQuotumPerDag().or(() -> standaardQuotumPerDag).ifPresent(quotum -> {
            OffsetDateTime beginVanDeDag = LocalDate.now(ZoneOffset.UTC).atStartOfDay().atOffset(ZoneOffset.UTC);

            if (notificatieRepository.telAangenomenSinds(dvId, beginVanDeDag) >= quotum) {
                throw new QuotumOverschredenException(quotum);
            }
        });
    }
}
