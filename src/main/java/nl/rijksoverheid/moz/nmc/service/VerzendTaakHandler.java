package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLConfiguratieException;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLVerzendAdapter;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLVerzendException;
import nl.rijksoverheid.moz.nmc.client.profielservice.GeenEmailadresGevondenException;
import nl.rijksoverheid.moz.nmc.client.profielservice.PartijIdentificatie;
import nl.rijksoverheid.moz.nmc.client.profielservice.PartijNietGevondenException;
import nl.rijksoverheid.moz.nmc.client.profielservice.ProfielServiceAdapter;
import nl.rijksoverheid.moz.nmc.client.profielservice.ProfielServiceException;
import nl.rijksoverheid.moz.nmc.controller.IdentificatieType;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Ontvanger;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import nl.rijksoverheid.moz.nmc.domain.Reden;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Verstuurt een aangenomen notificatie. Drie stappen: in een eigen transactie de overgang naar
 * {@code in-verzending} met een geplande poging; daarbuiten ontsleutelen, bij centrale regie de
 * Profielservice-lookup, en NotifyNL met het poging-id als {@code reference}; in een tweede
 * transactie de overgang naar {@code verzonden}, het NotifyNL-id op de poging, de navraagtaak en het
 * afronden van de verzendtaak. De HTTP-aanroep naar NotifyNL valt nooit binnen een transactie.
 * <p>
 * Dezelfde taak doet de herverzending: vanuit {@code verzonden} na een tijdelijke of technische fout op de
 * eerste poging maakt de claim de tweede poging aan ({@code verzonden} naar {@code verzonden}). Na
 * {@code geldig_tot} wordt geen poging meer gestart en eindigt de notificatie in {@code verlopen}; een
 * poging die al bij NotifyNL ligt loopt door.
 * <p>
 * Een fout over het adres of de partij is terminaal; een storing bij NotifyNL of de Profielservice
 * stelt de taak uit zonder dat het de notificatie een poging kost. Een herclaim na een verlopen lease
 * hergebruikt de poging en zoekt eerst op {@code reference}, zodat een al aangeboden e-mail niet nog
 * eens gaat.
 */
@ApplicationScoped
public class VerzendTaakHandler implements TaakHandler {

    static final String PAYLOAD_POGING_ID = "pogingId";

    private final Overgangsfunctie overgangsfunctie;
    private final PogingRepository pogingRepository;
    private final TaakRepository taakRepository;
    private final TaakClaimer taakClaimer;
    private final Sleutelbeheer sleutelbeheer;
    private final ProfielServiceAdapter profielServiceAdapter;
    private final NotifyNLVerzendAdapter verzendAdapter;
    private final Duration uitstel;
    private final Navraagschema navraagschema;

    public VerzendTaakHandler(Overgangsfunctie overgangsfunctie, PogingRepository pogingRepository,
                              TaakRepository taakRepository, TaakClaimer taakClaimer, Sleutelbeheer sleutelbeheer,
                              ProfielServiceAdapter profielServiceAdapter, NotifyNLVerzendAdapter verzendAdapter,
                              Navraagschema navraagschema,
                              @ConfigProperty(name = "nmc.taak.uitstel") Duration uitstel) {
        this.overgangsfunctie = overgangsfunctie;
        this.pogingRepository = pogingRepository;
        this.taakRepository = taakRepository;
        this.taakClaimer = taakClaimer;
        this.sleutelbeheer = sleutelbeheer;
        this.profielServiceAdapter = profielServiceAdapter;
        this.verzendAdapter = verzendAdapter;
        this.uitstel = uitstel;
        this.navraagschema = navraagschema;
    }

    @Override
    public TaakSoort soort() {
        return TaakSoort.VERZENDEN;
    }

    /**
     * Een uitgeputte verzendtaak eindigt in {@code technisch-mislukt} (ADR 0024, beslispunt 2), zodat de
     * Dienstverlener een eindstatus krijgt. Vanuit {@code aangenomen} loopt dat via
     * {@code in-verzending}, omdat er geen directe overgang is. Een notificatie die al verder is, houdt
     * haar status.
     */
    @Override
    public boolean uitgeput(Taak taak) {
        Notificatie notificatie = overgangsfunctie.vergrendel(taak.getNotificatieId());

        if (notificatie.getStatus() == NotificatieStatus.AANGENOMEN) {
            overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.IN_VERZENDING, null);
        }

        overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.TECHNISCH_MISLUKT, Reden.TECHNISCH);

        return true;
    }

    // De LDV-registratie van de verzending ontbreekt nog: de @Logboek-interceptor leest de headers
    // van een REST-aanroep en werkt niet in een worker. Dat vraagt een eigen registratie-API.
    @Override
    public TaakUitkomst voerUit(Taak taak, Lease lease) {
        Verzending verzending = QuarkusTransaction.requiringNew().call(() -> bereidVoor(taak));

        if (verzending == null) {
            return TaakUitkomst.alAfgerond();
        }

        lease.verleng();

        UUID notifyId;

        try {
            Optional<UUID> alAangeboden = verzending.herclaim()
                    ? verzendAdapter.zoekOpReference(verzending.pogingId().toString())
                    : Optional.empty();

            if (alAangeboden.isPresent()) {
                notifyId = alAangeboden.get();
            } else if (verzending.verlopen()) {
                // Het uitstel na een storing liep voorbij geldig_tot, en de poging ligt niet bij NotifyNL.
                return rondAfAls(taak, verzending, NotificatieStatus.VERLOPEN, Reden.VERLOPEN);
            } else {
                String emailAdres = emailAdres(verzending);

                if (emailAdres == null) {
                    return rondAfAls(taak, verzending, NotificatieStatus.NIET_BEZORGBAAR, Reden.GEEN_CONTACTGEGEVENS);
                }

                lease.verleng();
                notifyId = verzendAdapter.verstuurEmail(emailAdres, verzending.templateId(), verzending.personalisation(),
                        verzending.pogingId().toString());
            }
        } catch (ProfielServiceException e) {
            Log.warnf(e, "Profielservice niet beschikbaar voor notificatie %s; verzendtaak uitgesteld", verzending.notificatieId());

            return uitgesteld();
        } catch (NotifyNLConfiguratieException e) {
            Log.errorf(e, "NotifyNL-configuratie ongeldig; verzendtaak voor notificatie %s uitgesteld", verzending.notificatieId());

            return uitgesteld();
        } catch (NotifyNLVerzendException e) {
            // Een 4xx over het adres is een uitkomst van deze poging; 401, 403, 429, 5xx en een
            // verbindingsfout betreffen de aanroep en kosten de notificatie geen poging.
            if (e.status().filter(VerzendTaakHandler::isAdresfout).isPresent()) {
                Log.warnf("NotifyNL weigert het adres van notificatie %s (%s)", verzending.notificatieId(), e.getMessage());

                return rondAfAls(taak, verzending, NotificatieStatus.NIET_BEZORGBAAR, Reden.ONBEREIKBAAR);
            }

            Log.warnf(e, "NotifyNL niet beschikbaar voor notificatie %s; verzendtaak uitgesteld", verzending.notificatieId());

            return uitgesteld();
        }

        UUID definitiefNotifyId = notifyId;
        QuarkusTransaction.requiringNew().run(() -> rondVerzendingAf(taak, verzending, definitiefNotifyId));

        return TaakUitkomst.alAfgerond();
    }

    /**
     * Eerste transactie: {@code aangenomen} naar {@code in-verzending} met een geplande poging, of bij
     * een herclaim de bestaande geplande poging. Is de notificatie inmiddels verder (een receipt vóór de
     * verzend-commit, of geannuleerd), dan is er niets te verzenden en wordt de taak afgerond.
     *
     * @return null als de taak is afgerond zonder verzending
     */
    private Verzending bereidVoor(Taak taak) {
        Notificatie notificatie = overgangsfunctie.vergrendel(taak.getNotificatieId());
        Optional<Poging> laatste = pogingRepository.findLaatsteVan(notificatie.getId());
        OffsetDateTime nu = OffsetDateTime.now(ZoneOffset.UTC);
        Poging poging;
        boolean herclaim;

        switch (notificatie.getStatus()) {
            case AANGENOMEN -> {
                if (notificatie.isVerlopen(nu)) {
                    return verloop(taak, notificatie);
                }

                overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.IN_VERZENDING, null);
                poging = new Poging(notificatie.getId(), volgendPogingnummer(notificatie.getId()));
                pogingRepository.persist(poging);
                herclaim = false;
            }
            case IN_VERZENDING -> {
                poging = laatste.orElseThrow(() -> new IllegalStateException(
                        "Notificatie " + notificatie.getId() + " staat op in-verzending zonder poging"));
                herclaim = true;
            }
            case VERZONDEN -> {
                // Een herverzending: de tweede poging, of bij een herclaim de al geplande.
                if (laatste.filter(p -> p.getStatus() == PogingStatus.GEPLAND).isPresent()) {
                    poging = laatste.get();
                    herclaim = true;
                } else if (laatste.filter(VerzendTaakHandler::magHerverzonden).isPresent()) {
                    if (notificatie.isVerlopen(nu)) {
                        return verloop(taak, notificatie);
                    }

                    overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.VERZONDEN, null);
                    poging = new Poging(notificatie.getId(), volgendPogingnummer(notificatie.getId()));
                    pogingRepository.persist(poging);
                    herclaim = false;
                } else {
                    return rondAfZonderVerzending(taak, notificatie);
                }
            }
            default -> {
                return rondAfZonderVerzending(taak, notificatie);
            }
        }

        if (notificatie.getTemplateId() == null || notificatie.getRegie() == null) {
            throw new IllegalStateException("Notificatie " + notificatie.getId() + " heeft geen verzendgegevens");
        }

        Ontvanger ontvanger = sleutelbeheer.ontsleutelOntvanger(notificatie.getId(), notificatie.getVersleuteldeGegevens());
        Map<String, String> personalisation = sleutelbeheer.ontsleutelPersonalisation(notificatie.getId(),
                notificatie.getVersleuteldeGegevens());

        return new Verzending(notificatie.getId(), notificatie.getDvId(), poging.getId(), herclaim,
                herclaim && notificatie.isVerlopen(nu), ontvanger,
                Regie.valueOf(notificatie.getRegie()), notificatie.getDienstverlenerNaam(), notificatie.getDienst(),
                notificatie.getTemplateId(), personalisation, taak.getTraceId());
    }

    // Eén herverzending in totaal: alleen na een tijdelijke of technische fout op de eerste poging.
    static boolean magHerverzonden(Poging poging) {
        return poging.getNummer() == 1
                && (poging.getStatus() == PogingStatus.TIJDELIJK_MISLUKT || poging.getStatus() == PogingStatus.TECHNISCH_MISLUKT);
    }

    private Verzending verloop(Taak taak, Notificatie notificatie) {
        Log.infof("Notificatie %s is verlopen (geldig tot %s); geen verzending gestart", notificatie.getId(),
                notificatie.getGeldigTot());
        overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.VERLOPEN, Reden.VERLOPEN);
        taakClaimer.rondAf(taak);

        return null;
    }

    private Verzending rondAfZonderVerzending(Taak taak, Notificatie notificatie) {
        Log.infof("Notificatie %s staat op %s; verzendtaak %d afgerond zonder verzending",
                notificatie.getId(), notificatie.getStatus(), taak.getId());
        taakClaimer.rondAf(taak);

        return null;
    }

    private int volgendPogingnummer(UUID notificatieId) {
        return pogingRepository.findLaatsteVan(notificatieId).map(p -> p.getNummer() + 1).orElse(1);
    }

    /**
     * @return het adres, of null als de partij of het adres er niet is (terminaal)
     * @throws ProfielServiceException bij een storing, die uitstel oplevert
     */
    private String emailAdres(Verzending verzending) {
        if (verzending.regie() == Regie.DECENTRAAL) {
            return verzending.ontvanger().waarde();
        }

        try {
            // Het adres uit de Profielservice wordt alleen gebruikt en niet opgeslagen.
            return profielServiceAdapter.zoekEmailAdres(new PartijIdentificatie(
                    IdentificatieType.valueOf(verzending.ontvanger().soort().name()), verzending.ontvanger().waarde(),
                    verzending.dienstverlener(), verzending.dienst()));
        } catch (PartijNietGevondenException | GeenEmailadresGevondenException e) {
            Log.infof("Geen contactgegevens voor notificatie %s: %s", verzending.notificatieId(), e.getMessage());

            return null;
        }
    }

    /**
     * Tweede transactie, onder de rijvergrendeling. Kwam een receipt eerder binnen dan deze commit, dan
     * draagt de poging het id al en is de status al verder; dan wordt hier alleen een afwijkend id als
     * duplicaat vastgelegd.
     */
    private void rondVerzendingAf(Taak taak, Verzending verzending, UUID notifyId) {
        Notificatie notificatie = overgangsfunctie.vergrendel(verzending.notificatieId());
        Poging poging = pogingRepository.findById(verzending.pogingId());
        pogingRepository.herlaad(poging);
        OffsetDateTime nu = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

        if (poging.getNotifyId() == null) {
            poging.markeerVerzonden(notifyId, nu);
        } else if (!poging.hoortBij(notifyId)) {
            poging.registreerDuplicaat(notifyId);
        }

        if (notificatie.getStatus() == NotificatieStatus.IN_VERZENDING) {
            overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.VERZONDEN, null);
        }

        // De navraag bewaakt de eindstatus als de receipt uitblijft; is die er al, dan is hij overbodig.
        if (notificatie.getStatus() == NotificatieStatus.VERZONDEN) {
            taakRepository.persist(new Taak(TaakSoort.RECONCILIEREN, verzending.dvId(), notificatie.getId(),
                    navraagschema.eerste(poging.getVerzondenOp()), verzending.traceId(), Map.of(PAYLOAD_POGING_ID, poging.getId().toString())));
        }

        taakClaimer.rondAf(taak);
    }

    private TaakUitkomst rondAfAls(Taak taak, Verzending verzending, NotificatieStatus naar, Reden reden) {
        QuarkusTransaction.requiringNew().run(() -> {
            overgangsfunctie.voerUit(verzending.notificatieId(), naar, reden);
            taakClaimer.rondAf(taak);
        });

        return TaakUitkomst.alAfgerond();
    }

    private TaakUitkomst uitgesteld() {
        return TaakUitkomst.uitgesteld(OffsetDateTime.now(ZoneOffset.UTC).plus(uitstel), false);
    }

    // 400 en 422: NotifyNL keurt het verzoek af, in de praktijk het adres. 401, 403 en 429 zijn de
    // aanroep of het budget, en 5xx is NotifyNL zelf.
    private static boolean isAdresfout(int status) {
        return status == 400 || status == 422;
    }

    private record Verzending(UUID notificatieId, UUID dvId, UUID pogingId, boolean herclaim, boolean verlopen,
                              Ontvanger ontvanger, Regie regie, String dienstverlener, String dienst, String templateId,
                              Map<String, String> personalisation, String traceId) {
    }
}
