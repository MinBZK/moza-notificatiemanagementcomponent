package nl.rijksoverheid.moz.nmc.service.aanname;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import nl.rijksoverheid.moz.nmc.domain.Dienstverlener;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.helper.HashHelper;
import nl.rijksoverheid.moz.nmc.repository.DienstverlenerRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.service.DvProvider;
import nl.rijksoverheid.moz.nmc.service.Overgangsfunctie;
import nl.rijksoverheid.moz.nmc.service.Verzendbeleid;
import nl.rijksoverheid.moz.nmc.service.sleutel.Sleutelbeheer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Neemt een notificatie aan: notificatie op {@code aangenomen} met event 0, de versleutelde payload en
 * de verzendtaak, in één transactie. Het antwoord aan de aanroeper volgt op de commit; de verzending
 * doet de worker. De aanname roept de Profielservice en NotifyNL niet aan.
 * <p>
 * Het quotum per dienstverlener per dag komt uit {@code dienstverlener.quotum_per_dag}, anders uit
 * {@code nmc.aanname.quotum-per-dag}; zonder beide is er geen quotum. Eager bij het opstarten, zodat
 * een ongeldig standaardquotum de applicatie niet laat starten.
 */
@Startup
@ApplicationScoped
public class AannameService {

    // De doelen waarvoor de HMAC's dienen; elk doel krijgt een eigen sleutel uit de pepper.
    private static final String HMAC_DOEL_DV_REF = "nmc:dv-ref";
    private static final String HMAC_DOEL_PAYLOAD = "nmc:aanname-payload";

    private final Overgangsfunctie overgangsfunctie;
    private final Sleutelbeheer sleutelbeheer;
    private final DvProvider dvProvider;
    private final DienstverlenerRepository dienstverlenerRepository;
    private final NotificatieRepository notificatieRepository;
    private final TaakRepository taakRepository;
    private final Verzendbeleid verzendbeleid;
    private final Optional<Integer> standaardQuotumPerDag;
    private final Clock clock;
    private final HashHelper hashHelper;

    public AannameService(
            Overgangsfunctie overgangsfunctie,
            Sleutelbeheer sleutelbeheer,
            DvProvider dvProvider,
            DienstverlenerRepository dienstverlenerRepository,
            NotificatieRepository notificatieRepository,
            TaakRepository taakRepository,
            Verzendbeleid verzendbeleid,
            Clock clock,
            HashHelper hashHelper,
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
        this.verzendbeleid = verzendbeleid;
        this.standaardQuotumPerDag = standaardQuotumPerDag;
        this.clock = clock;
        this.hashHelper = hashHelper;
    }

    /**
     * @return het id van de aangenomen notificatie
     * @throws QuotumOverschredenException als de dienstverlener zijn quotum voor vandaag heeft bereikt
     */
    @Transactional
    public UUID neemAan(AannameOpdracht opdracht) {
        return neemAan(opdracht, null);
    }

    /**
     * @param dvRef de eigen referentie van de dienstverlener voor deze aanvraag; null zonder referentie
     * @return het id van de aangenomen notificatie, of van de notificatie die deze dienstverlener eerder met
     *         dezelfde referentie en dezelfde inhoud aanbood; dan wordt niets aangemaakt en telt de aanvraag
     *         niet voor het quotum
     * @throws QuotumOverschredenException als de dienstverlener zijn quotum voor vandaag heeft bereikt
     */
    @Transactional
    public UUID neemAan(AannameOpdracht opdracht, String dvRef) {
        UUID dvId = dvProvider.huidigeDvId();
        byte[] dvRefHmac = dvRef == null ? null : hashHelper.hmac(HMAC_DOEL_DV_REF, dvId + ":" + dvRef);
        byte[] payloadHash = dvRef == null ? null : hashHelper.hmac(HMAC_DOEL_PAYLOAD, canoniek(opdracht));

        if (dvRefHmac != null) {
            Optional<UUID> eerder = notificatieRepository.vergrendelEnZoekOpDvRef(dvId, dvRefHmac, payloadHash);

            if (eerder.isPresent()) {
                Log.infof("Herhaalde aanvraag van dienstverlener %s; notificatie %s bestond al", dvId, eerder.get());

                return eerder.get();
            }
        }

        toetsQuotum(dvId);

        // De versleutelde gegevens gaan vóór de aanname op de entity, zodat de insert ze meeneemt.
        Notificatie notificatie = new Notificatie(dvId, OffsetDateTime.now(clock));

        if (dvRefHmac != null) {
            notificatie.bewaarDvRef(dvRefHmac, payloadHash, HashHelper.PEPPER_VERSIE);
        }

        notificatie.bewaarVersleuteldeGegevens(
                sleutelbeheer.versleutel(notificatie.getId(), opdracht.ontvanger(), opdracht.berichtgegevens()));
        // Op de notificatie en niet in de taak, zodat ook een later geplande verzendtaak kan versturen.
        notificatie.bewaarVerzendgegevens(
                opdracht.berichtType().getTemplateId(),
                opdracht.regie().name(),
                opdracht.dienstverlener(),
                opdracht.dienst());
        notificatie.bewaarVerzendbeleid(
                opdracht.berichtType().name(),
                notificatie.getAangenomenOp().plus(verzendbeleid.geldigheid(opdracht.berichtType())));
        overgangsfunctie.neemAan(notificatie);

        taakRepository.persist(new Taak(
                TaakSoort.VERZENDEN,
                dvId,
                notificatie.getId(),
                OffsetDateTime.now(clock),
                UUID.randomUUID().toString(),
                null));

        return notificatie.getId();
    }

    // De inhoud van de aanvraag in een vaste vorm, zodat dezelfde aanvraag altijd dezelfde hash geeft. Elk
    // veld draagt zijn lengte, zodat twee verschillende aanvragen nooit dezelfde tekst opleveren, en de
    // berichtgegevens staan op naam gesorteerd.
    private static String canoniek(AannameOpdracht opdracht) {
        StringBuilder tekst = new StringBuilder();
        voegToe(tekst, opdracht.regie().name());
        voegToe(tekst, opdracht.ontvanger().soort().name());
        voegToe(tekst, opdracht.ontvanger().waarde());
        voegToe(tekst, opdracht.dienstverlener());
        voegToe(tekst, opdracht.dienst());
        voegToe(tekst, opdracht.berichtType().name());

        if (opdracht.berichtgegevens() != null) {
            new TreeMap<>(opdracht.berichtgegevens()).forEach((naam, waarde) -> {
                voegToe(tekst, naam);
                voegToe(tekst, waarde);
            });
        }

        return tekst.toString();
    }

    private static void voegToe(StringBuilder tekst, String veld) {
        tekst.append(veld == null ? "-" : veld.length() + ":" + veld).append('|');
    }

    // Per kalenderdag in UTC. Het quotum uit het register gaat voor; zonder quotum daar geldt de standaard.
    // De telling is niet vergrendeld: gelijktijdige aanvragen op meerdere pods kunnen het quotum met
    // enkele overschrijden. Het quotum begrenst, het rekent niet af, dus dat is aanvaard.
    private void toetsQuotum(UUID dvId) {
        Dienstverlener dienstverlener = dienstverlenerRepository.findById(dvId);

        if (dienstverlener == null) {
            throw new IllegalStateException("Dienstverlener " + dvId + " staat niet in het register");
        }

        dienstverlener.getQuotumPerDag().or(() -> standaardQuotumPerDag).ifPresent(quotum -> {
            OffsetDateTime beginVanDeDag = LocalDate.now(clock).atStartOfDay().atOffset(ZoneOffset.UTC);

            if (notificatieRepository.telAangenomenSinds(dvId, beginVanDeDag) >= quotum) {
                Log.infof("Dienstverlener %s heeft het quotum van %d notificaties per dag bereikt", dvId, quotum);

                throw new QuotumOverschredenException(quotum);
            }
        });
    }
}
