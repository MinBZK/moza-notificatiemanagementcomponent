package nl.rijksoverheid.moz.nmc.testhelper;

import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Brengt de gedeelde testdatabase terug naar één vaste begintoestand. Elke {@code @QuarkusTest} deelt
 * dezelfde database; een test die hier begint, hangt niet af van wat een eerdere test achterliet of
 * van de volgorde waarin de klassen draaien.
 * <p>
 * De begintoestand: geen notificaties, pogingen, events, taken, budgetrijen, bevestigingen of
 * leverposities, alleen de eventpartities uit de migraties, en in het register alleen de dienstverlener
 * uit de migratie, zonder webhook, quotum of eigen cursorleeftijd. Ook de taken die de migraties
 * plaatsen (controle en onderhoud) zijn weg; een test die er een nodig heeft, maakt hem zelf.
 */
public final class TestDatabase {

    // Een nieuwe tabel met gegevens die een test kan wijzigen, hoort in deze lijst.
    private static final String MUTEERBARE_TABELLEN =
            "notificatie, poging, event, taak, verzendbudget, bevestiging, webhookpositie";

    // De partities van event die de migraties aanmaken; elke andere komt van een test.
    private static final List<String> PARTITIES_UIT_DE_MIGRATIES = List.of("event_0", "event_standaard");

    // De naam komt in DDL; alleen de vorm die het partitiebeheer zelf maakt wordt verwijderd.
    private static final Pattern BEREIKPARTITIE = Pattern.compile("event_[0-9]+");

    private TestDatabase() {}

    /** Leegt de database tot de begintoestand, in een eigen transactie. */
    public static void leeg() {
        EntityManager entityManager =
                Arc.container().instance(EntityManager.class).get();

        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery("TRUNCATE " + MUTEERBARE_TABELLEN).executeUpdate();
            verwijderPartitiesVanTests(entityManager);
            entityManager
                    .createNativeQuery("DELETE FROM dienstverlener WHERE id <> ?1")
                    .setParameter(1, NotificatieFixtures.DV_ID)
                    .executeUpdate();
            entityManager
                    .createNativeQuery("UPDATE dienstverlener SET webhook_url = NULL, webhook_max_mislukkingen = NULL,"
                            + " webhook_bundel = NULL, quotum_per_dag = NULL, max_cursorleeftijd = NULL WHERE id = ?1")
                    .setParameter(1, NotificatieFixtures.DV_ID)
                    .executeUpdate();
        });
    }

    @SuppressWarnings("unchecked")
    private static void verwijderPartitiesVanTests(EntityManager entityManager) {
        List<String> partities = entityManager
                .createNativeQuery("SELECT c.relname FROM pg_inherits i"
                        + " JOIN pg_class c ON c.oid = i.inhrelid WHERE i.inhparent = CAST('event' AS regclass)")
                .getResultList();

        for (String partitie : partities) {
            if (!PARTITIES_UIT_DE_MIGRATIES.contains(partitie)
                    && BEREIKPARTITIE.matcher(partitie).matches()) {
                entityManager.createNativeQuery("DROP TABLE " + partitie).executeUpdate();
            }
        }
    }
}
