package nl.rijksoverheid.moz.nmc.controller;

import io.quarkus.logging.Log;
import jakarta.ws.rs.core.Response;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext;
import nl.rijksoverheid.moz.nmc.api.LogboekApi;
import nl.rijksoverheid.moz.nmc.api.model.LogboekBetrokkene;
import nl.rijksoverheid.moz.nmc.api.model.LogboekVerwerking;
import nl.rijksoverheid.moz.nmc.api.model.LogboekVerwerkingen;
import nl.rijksoverheid.moz.nmc.helper.HashHelper;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import nl.rijksoverheid.moz.nmc.repository.LogboekLezer;
import nl.rijksoverheid.moz.nmc.service.Leesregel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Inzage in het Logboek Dataverwerkingen per betrokkene, voor de demo op het Fundament Fieldlab. Niet
 * afgeschermd en daarom standaard uit; zelfde contract als de inzage van het berichtenmagazijn van het FBS.
 */
public class LogboekController implements LogboekApi {

    private final boolean aan;
    private final LogboekLezer logboekLezer;
    private final HashHelper hashHelper;
    private final LogboekContext logboekContext;

    public LogboekController(@ConfigProperty(name = "nmc.logboek.inzage.enabled", defaultValue = "false") boolean aan,
                             LogboekLezer logboekLezer, HashHelper hashHelper, LogboekContext logboekContext) {
        this.aan = aan;
        this.logboekLezer = logboekLezer;
        this.hashHelper = hashHelper;
        this.logboekContext = logboekContext;
    }

    @Override
    // TODO #754: processingActivityId is een placeholder, net als bij de andere @Logboek-acties.
    @Logboek(name = "logboekInzien", processingActivityId = "https://mijnoverheidzakelijk.nl/verwerkingsactiviteiten/TODO-NMC")
    public Response logboekVerwerkingenOpvragen(String xBetrokkene, OffsetDateTime van, OffsetDateTime tot) {
        // Het formaat is al door de @Pattern op de header gecontroleerd: <TYPE>:<WAARDE>.
        int scheiding = xBetrokkene.indexOf(':');
        String type = xBetrokkene.substring(0, scheiding);
        String waarde = xBetrokkene.substring(scheiding + 1);
        String hash = hashHelper.hashIdentifier(waarde);
        // De inzage zelf is ook een verwerking van deze betrokkene; de interceptor eist een betrokkene.
        logboekContext.setDataSubjectId(hash);
        logboekContext.setDataSubjectType(type);

        if (!aan) {
            throw Problems.notFound("Inzage staat uit", "De inzage in het logboek is op deze omgeving niet aangezet");
        }
        if (van != null && tot != null && !van.isBefore(tot)) {
            throw Problems.badRequest("Ongeldige periode", "van moet vóór tot liggen");
        }

        List<Leesregel.Verwerking> verwerkingen;
        try {
            verwerkingen = Leesregel.pasToe(logboekLezer.lees(type, hash, instant(van), instant(tot)));
        } catch (LogboekLezer.LogboekNietLeesbaarException e) {
            Log.error("Het logboek kon niet worden gelezen", e);
            throw Problems.serverError("Logboek niet leesbaar", "Het logboek kan op dit moment niet worden gelezen");
        }

        LogboekVerwerkingen antwoord = new LogboekVerwerkingen(
                new LogboekBetrokkene(LogboekBetrokkene.TypeEnum.fromValue(type), waarde),
                verwerkingen.stream().map(LogboekController::naarApi).toList());

        return Response.ok(antwoord).build();
    }

    private static LogboekVerwerking naarApi(Leesregel.Verwerking verwerking) {
        return new LogboekVerwerking(OffsetDateTime.ofInstant(verwerking.tijdstip(), ZoneOffset.UTC), verwerking.handeling(),
                verwerking.geslaagd() ? LogboekVerwerking.UitkomstEnum.GESLAAGD : LogboekVerwerking.UitkomstEnum.MISLUKT)
                .verwerkingsactiviteit(verwerking.verwerkingsactiviteit())
                .foutcategorie(verwerking.foutcategorie());
    }

    private static Instant instant(OffsetDateTime tijdstip) {
        return tijdstip == null ? null : tijdstip.toInstant();
    }
}
