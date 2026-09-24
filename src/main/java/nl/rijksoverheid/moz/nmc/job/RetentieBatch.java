package nl.rijksoverheid.moz.nmc.job;

import io.quarkus.logging.Log;
import nl.rijksoverheid.moz.nmc.repository.Kandidaat;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Eén batch van de retentiejob: claimt verlopen notificaties onder rijlock, meldt degene zonder
 * eindstatus en verwijdert daarna precies die rijen.
 * <p>
 * Houdt zijn eigen uitkomst vast in plaats van die terug te geven, want bij een teruggerolde
 * transactie is er geen retourwaarde terwijl de geclaimde ids en de al geschreven meldingen dan juist
 * bekend moeten zijn. Eén batch is eenmalig te gebruiken; {@link NotificatieRetentieScheduler} maakt
 * er per ronde een nieuwe.
 */
class RetentieBatch {

    // Begrenst hoeveel rijen per transactie worden verwijderd. Eén onbegrensde DELETE over een grote
    // achterstand overschrijdt de JTA-transactietimeout van 60s en rolt dan alles terug.
    static final int GROOTTE = 1000;

    private final NotificatieRepository notificatieRepository;
    private final int meldbudget;

    private final List<UUID> geclaimd = new ArrayList<>();
    private int zonderEindstatus;
    private int gemeld;
    private int verwijderd;

    RetentieBatch(NotificatieRepository notificatieRepository, int meldbudget) {
        this.notificatieRepository = notificatieRepository;
        this.meldbudget = meldbudget;
    }

    /**
     * SKIP LOCKED omdat er in productie minimaal drie pods draaien: zonder die clausule blokkeren ze
     * op elkaars rijlocks in plaats van de achterstand te verdelen. De lock sluit tegelijk het gat
     * tussen claim en DELETE, want een gelijktijdige verwerkAfleverstatus wacht erop; de DELETE hoeft
     * het retentiepredicaat daarom niet te herhalen.
     * <p>
     * Melden gebeurt vóór de DELETE en over precies dezelfde geclaimde rijen, zodat een verwijderde
     * rij nooit ongemeld blijft. Een dubbele melding na een teruggerolde batch is het alternatief.
     *
     * @param uitgesloten ids die een eerdere batch in deze run heeft overgeslagen
     */
    void verwijder(OffsetDateTime grens, List<UUID> uitgesloten) {
        List<UUID> ids = notificatieRepository.claimVerlopen(grens, GROOTTE, uitgesloten);
        geclaimd.addAll(ids);

        if (ids.isEmpty()) {
            return;
        }

        List<Kandidaat> kandidaten = notificatieRepository.zoekKandidaten(ids).stream()
                .filter(kandidaat -> !kandidaat.status().isDefinitief())
                .toList();
        zonderEindstatus = kandidaten.size();
        gemeld = Math.min(zonderEindstatus, meldbudget);
        kandidaten.subList(0, gemeld).forEach(RetentieBatch::meld);

        int weg = notificatieRepository.verwijderOpId(ids);

        // Hoort niet te kunnen: de rijen staan onder rijlock en de DELETE gaat op precies die ids.
        // Zonder deze controle blijft de lus van de scheduler draaien, want die kijkt naar het aantal
        // geclaimde rijen; als mislukte batch behandelen laat de bestaande afhandeling grijpen.
        if (weg != ids.size()) {
            throw new IllegalStateException("Retentiejob: " + ids.size() + " rijen geclaimd onder "
                    + "rijlock maar " + weg + " verwijderd (grens=" + grens + ")");
        }

        verwijderd = weg;
    }

    /** De ids die deze batch onder rijlock heeft genomen, ook na een teruggerolde transactie. */
    List<UUID> geclaimd() {
        return List.copyOf(geclaimd);
    }

    boolean vol() {
        return geclaimd.size() == GROOTTE;
    }

    int zonderEindstatus() {
        return zonderEindstatus;
    }

    int gemeld() {
        return gemeld;
    }

    int verwijderd() {
        return verwijderd;
    }

    // WARN en geen ERROR: verlopen zonder eindstatus is informatie, geen storing in de NMC. Key=value
    // zodat er een dashboard op te bouwen is zonder vrije tekst te parsen; er is geen JSON-logging.
    private static void meld(Kandidaat kandidaat) {
        // Een lege externalReference is een andere diagnose dan een gevulde: dan is de notificatie
        // nooit bij NotifyNL aangeboden, in plaats van wel aangeboden zonder uitkomst.
        Log.warnf("Retentiejob: notificatie verlopen zonder eindstatus notificatieId=%s "
                + "notifyNlReferentie=%s status=%s laatsteStatusUpdate=%s", kandidaat.id(),
                kandidaat.externalReference() != null ? kandidaat.externalReference() : "geen",
                kandidaat.status(), kandidaat.laatsteStatusUpdate());
    }
}
