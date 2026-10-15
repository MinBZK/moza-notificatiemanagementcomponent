package nl.rijksoverheid.moz.nmc.testhelper;

import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Schrijft en leest de takentabel, het verzendbudget en de dienstverleners buiten Hibernate om, voor
 * tests die een toestand willen die de claim zelf niet maakt. Elke query op {@code taak} noemt de soort.
 */
public final class TaakFixtures {

    private TaakFixtures() {
    }

    /** Verhoogt het claim-epoch, zoals een andere worker die de taak intussen claimt. */
    public static void claimDoorAndereWorker(EntityManager entityManager, TaakSoort taakSoort, long id) {
        entityManager.createNativeQuery("UPDATE taak SET claim_epoch = claim_epoch + 1 WHERE soort = ?1 AND id = ?2")
                .setParameter(1, taakSoort.name())
                .setParameter(2, id)
                .executeUpdate();
    }

    public static void zetDue(EntityManager entityManager, TaakSoort taakSoort, long id, OffsetDateTime due) {
        entityManager.createNativeQuery("UPDATE taak SET due = ?3 WHERE soort = ?1 AND id = ?2")
                .setParameter(1, taakSoort.name())
                .setParameter(2, id)
                .setParameter(3, due)
                .executeUpdate();
    }

    public static void zetLeaseTot(EntityManager entityManager, TaakSoort taakSoort, long id, OffsetDateTime tot) {
        entityManager.createNativeQuery("UPDATE taak SET lease_tot = ?3 WHERE soort = ?1 AND id = ?2")
                .setParameter(1, taakSoort.name())
                .setParameter(2, id)
                .setParameter(3, tot)
                .executeUpdate();
    }

    /** Vergrendelt de taakrij tot het einde van de transactie van de aanroeper. */
    public static void vergrendel(EntityManager entityManager, TaakSoort taakSoort, long id) {
        entityManager.createNativeQuery("SELECT id FROM taak WHERE soort = ?1 AND id = ?2 FOR UPDATE")
                .setParameter(1, taakSoort.name())
                .setParameter(2, id)
                .getResultList();
    }

    public static void leegVerzendbudget(EntityManager entityManager) {
        entityManager.createNativeQuery("DELETE FROM verzendbudget").executeUpdate();
    }

    /** De tokens van het enige tijdvak in de tabel. */
    public static int tokens(EntityManager entityManager) {
        return ((Number) entityManager.createNativeQuery("SELECT tokens FROM verzendbudget").getSingleResult()).intValue();
    }

    public static int tokens(EntityManager entityManager, OffsetDateTime tijdvak) {
        return ((Number) entityManager.createNativeQuery("SELECT tokens FROM verzendbudget WHERE tijdvak = ?1")
                .setParameter(1, tijdvak)
                .getSingleResult()).intValue();
    }

    public static int telBudgetrijen(EntityManager entityManager) {
        return ((Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM verzendbudget").getSingleResult()).intValue();
    }

    /** Voegt een dienstverlener toe als die er nog niet is. */
    public static void voegDienstverlenerToe(EntityManager entityManager, UUID id, String oin, String naam) {
        entityManager.createNativeQuery("INSERT INTO dienstverlener (id, oin, naam) VALUES (?1, ?2, ?3) "
                        + "ON CONFLICT (id) DO NOTHING")
                .setParameter(1, id)
                .setParameter(2, oin)
                .setParameter(3, naam)
                .executeUpdate();
    }

    public static int telDienstverleners(EntityManager entityManager, UUID id) {
        return ((Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM dienstverlener WHERE id = ?1")
                .setParameter(1, id)
                .getSingleResult()).intValue();
    }
}
