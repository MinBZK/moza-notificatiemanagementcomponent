package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLConfiguratieException;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLVerzendAdapter;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLVerzendException;
import nl.rijksoverheid.moz.nmc.client.profielservice.PartijIdentificatie;
import nl.rijksoverheid.moz.nmc.client.profielservice.ProfielServiceAdapter;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Ontvanger;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class NotificatieService {

    private final ProfielServiceAdapter profielServiceAdapter;
    private final NotifyNLVerzendAdapter verzendAdapter;
    private final PogingRepository pogingRepository;
    private final Overgangsfunctie overgangsfunctie;
    private final Sleutelbeheer sleutelbeheer;

    public NotificatieService(ProfielServiceAdapter profielServiceAdapter,
                               NotifyNLVerzendAdapter verzendAdapter,
                               PogingRepository pogingRepository,
                               Overgangsfunctie overgangsfunctie,
                               Sleutelbeheer sleutelbeheer) {
        this.profielServiceAdapter = profielServiceAdapter;
        this.verzendAdapter = verzendAdapter;
        this.pogingRepository = pogingRepository;
        this.overgangsfunctie = overgangsfunctie;
        this.sleutelbeheer = sleutelbeheer;
    }

    // TODO (buiten scope): deze transactie blijft open over de synchrone Profielservice- en
    // NotifyNL-aanroepen heen en houdt zolang een DB-connectie bezet. De consument-callback draait
    // inmiddels ná de commit (StatusUpdateVerzender); deze twee aanroepen niet.
    @Transactional
    public Notificatie versturen(NotificatieVersturenOpdracht opdracht) {
        String emailAdres = profielServiceAdapter.zoekEmailAdres(new PartijIdentificatie(
                opdracht.identificatieType(), opdracht.identificatieNummer(),
                opdracht.dienstverlener(), opdracht.dienst()));

        // Het adres uit de Profielservice wordt niet opgeslagen; op de rij staat het identificerend nummer.
        Ontvanger ontvanger = new Ontvanger(Ontvanger.Soort.valueOf(opdracht.identificatieType().name()),
                opdracht.identificatieNummer());

        return verstuurNaarEmail(emailAdres, ontvanger, opdracht.templateId(), opdracht.berichtgegevens(), opdracht.callbackUrl());
    }

    @Transactional
    public Notificatie verstuurDecentraal(DecentraleNotificatieVersturenOpdracht opdracht) {
        return verstuurNaarEmail(opdracht.emailAdres(), Ontvanger.email(opdracht.emailAdres()), opdracht.templateId(),
                opdracht.berichtgegevens(), opdracht.callbackUrl());
    }

    // Aanname, poging en overgang naar in-verzending worden geflusht vóór het versturen, zodat een
    // schrijffout geen verstuurde e-mail zonder record oplevert. Een fout bij de commit daarna kan
    // dat nog wel. De versleutelde gegevens gaan vóór de aanname op de entity, zodat de insert ze
    // meeneemt zonder tweede update.
    private Notificatie verstuurNaarEmail(String emailAdres, Ontvanger ontvanger, String templateId,
                                          Map<String, String> berichtgegevens, String callbackUrl) {
        Notificatie notificatie = new Notificatie(callbackUrl);
        notificatie.bewaarVersleuteldeGegevens(sleutelbeheer.versleutel(notificatie.getId(), ontvanger, berichtgegevens));
        overgangsfunctie.neemAan(notificatie);

        Poging poging = new Poging(notificatie.getId(), 1);
        pogingRepository.persist(poging);
        overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.IN_VERZENDING, null);

        UUID notifyId;
        try {
            notifyId = verzendAdapter.verstuurEmail(emailAdres, templateId, berichtgegevens);
        } catch (NotifyNLConfiguratieException | NotifyNLVerzendException e) {
            Log.error("Fout bij versturen van notificatie", e);
            throw new NotificatieException("Notificatie kon niet worden verstuurd.");
        }

        poging.markeerVerzonden(notifyId, OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.VERZONDEN, null);

        return notificatie;
    }
}
