package nl.rijksoverheid.moz.nmc.testhelper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import nl.rijksoverheid.moz.nmc.domain.Dienstverlener_;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.domain.TaakStatus;
import nl.rijksoverheid.moz.nmc.domain.Taak_;
import nl.rijksoverheid.moz.nmc.domain.VerzendbudgetTijdvak_;
import nl.rijksoverheid.moz.nmc.domain.Webhookpositie_;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Schrijft en leest de takentabel, het verzendbudget, de dienstverleners en de webhookpositie buiten de
 * claim om, voor tests die een toestand willen die de claim zelf niet maakt. Elke query op
 * {@code Taak} noemt de soort.
 */
public final class TaakFixtures {

    private static final String ZELFDE_TAAK = " where " + Taak_.TAAK_SOORT + " = :taakSoort and " + Taak_.ID + " = :id";

    private TaakFixtures() {
    }

    /** Verhoogt het claim-epoch, zoals een andere worker die de taak intussen claimt. */
    public static void claimDoorAndereWorker(EntityManager entityManager, TaakSoort taakSoort, long id) {
        entityManager.createQuery("update Taak set " + Taak_.CLAIM_EPOCH + " = " + Taak_.CLAIM_EPOCH + " + 1"
                        + ZELFDE_TAAK)
                .setParameter("taakSoort", taakSoort)
                .setParameter("id", id)
                .executeUpdate();
    }

    public static void zetDue(EntityManager entityManager, TaakSoort taakSoort, long id, OffsetDateTime due) {
        entityManager.createQuery("update Taak set " + Taak_.DUE + " = :due" + ZELFDE_TAAK)
                .setParameter("due", due)
                .setParameter("taakSoort", taakSoort)
                .setParameter("id", id)
                .executeUpdate();
    }

    public static void zetLeaseTot(EntityManager entityManager, TaakSoort taakSoort, long id, OffsetDateTime tot) {
        entityManager.createQuery("update Taak set " + Taak_.LEASE_TOT + " = :tot" + ZELFDE_TAAK)
                .setParameter("tot", tot)
                .setParameter("taakSoort", taakSoort)
                .setParameter("id", id)
                .executeUpdate();
    }

    public static void zetPogingen(EntityManager entityManager, TaakSoort taakSoort, long id, int pogingen) {
        entityManager.createQuery("update Taak set " + Taak_.POGINGEN + " = :pogingen" + ZELFDE_TAAK)
                .setParameter("pogingen", pogingen)
                .setParameter("taakSoort", taakSoort)
                .setParameter("id", id)
                .executeUpdate();
    }

    /** Zet de taak op {@code MISLUKT} zonder de pogingen uit te putten. */
    public static void markeerMislukt(EntityManager entityManager, TaakSoort taakSoort, long id) {
        entityManager.createQuery("update Taak set " + Taak_.STATUS + " = :mislukt" + ZELFDE_TAAK)
                .setParameter("mislukt", TaakStatus.MISLUKT)
                .setParameter("taakSoort", taakSoort)
                .setParameter("id", id)
                .executeUpdate();
    }

    /** Vergrendelt de taakrij tot het einde van de transactie van de aanroeper. */
    public static void vergrendel(EntityManager entityManager, TaakSoort taakSoort, long id) {
        entityManager.createQuery("from Taak" + ZELFDE_TAAK, Taak.class)
                .setParameter("taakSoort", taakSoort)
                .setParameter("id", id)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getSingleResult();
    }

    public static void leegVerzendbudget(EntityManager entityManager) {
        entityManager.createQuery("delete from VerzendbudgetTijdvak").executeUpdate();
    }

    /** De tokens van het enige tijdvak in de tabel. */
    public static int tokens(EntityManager entityManager) {
        return entityManager.createQuery("select " + VerzendbudgetTijdvak_.TOKENS + " from VerzendbudgetTijdvak",
                        Integer.class)
                .getSingleResult();
    }

    public static int tokens(EntityManager entityManager, OffsetDateTime tijdvak) {
        return entityManager.createQuery("select " + VerzendbudgetTijdvak_.TOKENS + " from VerzendbudgetTijdvak where "
                        + VerzendbudgetTijdvak_.TIJDVAK + " = :tijdvak", Integer.class)
                .setParameter("tijdvak", tijdvak)
                .getSingleResult();
    }

    public static int telBudgetrijen(EntityManager entityManager) {
        return entityManager.createQuery("select count(*) from VerzendbudgetTijdvak", Long.class)
                .getSingleResult()
                .intValue();
    }

    /** Voegt een dienstverlener toe als die er nog niet is. */
    public static void voegDienstverlenerToe(EntityManager entityManager, UUID id, String oin, String naam) {
        entityManager.createQuery("insert into Dienstverlener (" + Dienstverlener_.ID + ", " + Dienstverlener_.OIN
                        + ", " + Dienstverlener_.NAAM + ") values (:id, :oin, :naam) on conflict do nothing")
                .setParameter("id", id)
                .setParameter("oin", oin)
                .setParameter("naam", naam)
                .executeUpdate();
    }

    /** Zet het quotum per dag; {@code null} betekent geen quotum. */
    public static void zetQuotumPerDag(EntityManager entityManager, UUID dvId, Integer quotum) {
        entityManager.createQuery("update Dienstverlener set " + Dienstverlener_.QUOTUM_PER_DAG + " = :quotum where "
                        + Dienstverlener_.ID + " = :id")
                .setParameter("quotum", quotum)
                .setParameter("id", dvId)
                .executeUpdate();
    }

    /** Zet de webhook in het register; {@code null} betekent geen webhook of de default. */
    public static void zetWebhook(EntityManager entityManager, UUID dvId, String url, Integer maxMislukkingen,
                                  Integer bundel) {
        entityManager.createQuery("update Dienstverlener set " + Dienstverlener_.WEBHOOK_URL + " = :url, "
                        + Dienstverlener_.WEBHOOK_MAX_MISLUKKINGEN + " = :max, " + Dienstverlener_.WEBHOOK_BUNDEL
                        + " = :bundel where " + Dienstverlener_.ID + " = :id")
                .setParameter("url", url)
                .setParameter("max", maxMislukkingen)
                .setParameter("bundel", bundel)
                .setParameter("id", dvId)
                .executeUpdate();
    }

    /** Laat een lopende pauze van de webhook op {@code tot} eindigen. */
    public static void beeindigPauze(EntityManager entityManager, UUID dvId, OffsetDateTime tot) {
        entityManager.createQuery("update Webhookpositie set " + Webhookpositie_.GEPAUZEERD_TOT + " = :tot where "
                        + Webhookpositie_.DV_ID + " = :dvId and " + Webhookpositie_.GEPAUZEERD_TOT + " is not null")
                .setParameter("tot", tot)
                .setParameter("dvId", dvId)
                .executeUpdate();
    }

    public static int telDienstverleners(EntityManager entityManager, UUID id) {
        return entityManager.createQuery("select count(*) from Dienstverlener where " + Dienstverlener_.ID + " = :id",
                        Long.class)
                .setParameter("id", id)
                .getSingleResult()
                .intValue();
    }
}
