package nl.rijksoverheid.moz.nmc.service;

import nl.rijksoverheid.moz.nmc.repository.LogboekLezer.LogboekRij;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * De leesregel van het logboek: een logregel op {@code UNSET} zonder ERROR-child is geslaagd; een
 * logregel op {@code ERROR}, of met een ERROR-child, is mislukt. Een uitkomst-child (herkenbaar aan
 * {@code moza.ldv.uitkomst}) is zelf geen verwerking: hij hangt via trace en {@code parent_span_id} onder
 * zijn logregel. Zelfde regel als de inzage van het berichtenmagazijn van het FBS.
 */
public final class Leesregel {

    private static final String UITKOMST_MISLUKT = "mislukt";
    private static final String STATUS_ERROR = "ERROR";

    private Leesregel() {
    }

    public record Verwerking(Instant tijdstip, String handeling, String verwerkingsactiviteit, boolean geslaagd,
                             String foutcategorie) {
    }

    /** Eén verwerking per logregel, nieuwste eerst. */
    public static List<Verwerking> pasToe(List<LogboekRij> rijen) {
        Map<String, LogboekRij> mislukteUitkomsten = rijen.stream()
                .filter(r -> UITKOMST_MISLUKT.equals(r.uitkomstAttribuut()))
                .collect(Collectors.toMap(r -> sleutel(r.traceId(), r.parentSpanId()), Function.identity(), (a, b) -> a));
        List<LogboekRij> logregels = rijen.stream().filter(r -> r.uitkomstAttribuut() == null).toList();

        List<Verwerking> verwerkingen = new ArrayList<>();
        for (LogboekRij logregel : logregels) {
            verwerkingen.add(naarVerwerking(logregel, mislukteUitkomsten.get(sleutel(logregel.traceId(), logregel.spanId()))));
        }
        // Een uitkomst zonder logregel in het resultaat (bv. buiten de gevraagde periode) blijft zichtbaar
        // als mislukte verwerking, zodat de inzage niets weglaat.
        Set<String> bekend = logregels.stream().map(r -> sleutel(r.traceId(), r.spanId())).collect(Collectors.toSet());
        mislukteUitkomsten.entrySet().stream()
                .filter(e -> !bekend.contains(e.getKey()))
                .forEach(e -> verwerkingen.add(naarVerwerking(e.getValue(), e.getValue())));

        verwerkingen.sort(Comparator.comparing(Verwerking::tijdstip).reversed());

        return verwerkingen;
    }

    private static Verwerking naarVerwerking(LogboekRij logregel, LogboekRij mislukteUitkomst) {
        boolean mislukt = mislukteUitkomst != null || STATUS_ERROR.equals(logregel.status());
        // De ruwe exception.message van een logregel zelf kan gegevens uit het verzoek bevatten; alleen
        // die van een uitkomst-child is een vaste samenvatting.
        String foutcategorie = null;
        if (mislukt) {
            foutcategorie = mislukteUitkomst != null
                    ? Objects.requireNonNullElse(mislukteUitkomst.foutMelding(), mislukteUitkomst.foutType())
                    : logregel.foutType();
        }

        return new Verwerking(logregel.start(), logregel.naam(), logregel.activiteit(), !mislukt, foutcategorie);
    }

    private static String sleutel(String traceId, String spanId) {
        return traceId + "/" + spanId;
    }
}
