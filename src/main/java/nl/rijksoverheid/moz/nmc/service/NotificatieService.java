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
        // NotifyNL is leidend: de NMC registreert wat binnenkomt, ook als dat een eerder ontvangen
        // definitieve status weer "terugdraait" naar een niet-definitieve. Dat kan wijzen op een
        // dubbele of laat aangekomen callback bij NotifyNL zelf — de moeite van het opmerken waard,
        // maar geen reden om de nieuwe status te weigeren.
        if (huidigeStatus.isDefinitief() && !nieuweStatus.isDefinitief()) {
            Log.warnf("Notificatie %s ging van definitieve status %s terug naar niet-definitieve status "
                    + "%s (NotifyNL-referentie %s)", notificatie.getId(), huidigeStatus, nieuweStatus,
                    notifyNlNotificatieId);
        }
        notificatie.registreerStatus(nieuweStatus);

        // TODO #732: stuurStatusUpdate() doet tot 3 synchrone HTTP-pogingen binnen deze transactie.
        // Een JTA-timeout hier rolt de registreerStatus()-aanroep hierboven stilletjes terug — de
        // net verwerkte NotifyNL-uitkomst gaat dan verloren in plaats van dat er een fout opduikt.
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
