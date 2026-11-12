package nl.rijksoverheid.moz.nmc.testhelper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import nl.rijksoverheid.moz.nmc.domain.Bevestiging_;
import nl.rijksoverheid.moz.nmc.domain.Dienstverlener_;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.domain.TaakStatus;
import nl.rijksoverheid.moz.nmc.domain.Taak_;
import nl.rijksoverheid.moz.nmc.domain.VerzendbudgetTijdvak_;
import nl.rijksoverheid.moz.nmc.domain.Webhookpositie_;
import org.hibernate.Session;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Schrijft en leest de takentabel, het verzendbudget, de dienstverleners, de bevestigingen en de
 * webhookposities buiten de claim om, voor tests die een toestand willen die de claim zelf niet maakt. Elke query op
 * {@code Taak} noemt de soort.
 */
public final class TaakFixtures {

    private static final String ZELFDE_TAAK = " WHERE " + Taak_.TAAK_SOORT + " = :taakSoort AND " + Taak_.ID + " = :id";

    private TaakFixtures() {
    }

    /** Verhoogt het claim-epoch, zoals een andere worker die de taak intussen claimt. */
    public static void claimDoorAndereWorker(EntityManager entityManager, TaakSoort taakSoort, long id) {
        entityManager.createQuery("UPDATE Taak SET " + Taak_.CLAIM_EPOCH + " = " + Taak_.CLAIM_EPOCH + " + 1"
                        + ZELFDE_TAAK)
                .setParameter("taakSoort", taakSoort)
                .setParameter("id", id)
                .executeUpdate();
    }

    public static void zetDue(EntityManager entityManager, TaakSoort taakSoort, long id, OffsetDateTime due) {
        entityManager.createQuery("UPDATE Taak SET " + Taak_.DUE + " = :due" + ZELFDE_TAAK)
                .setParameter("due", due)
                .setParameter("taakSoort", taakSoort)
                .setParameter("id", id)
                .executeUpdate();
    }

    /** Zet de {@code due} van alle taken van deze soort. */
    public static void zetDue(EntityManager entityManager, TaakSoort taakSoort, OffsetDateTime due) {
        entityManager.createQuery("UPDATE Taak SET " + Taak_.DUE + " = :due WHERE " + Taak_.TAAK_SOORT + " = :taakSoort")
                .setParameter("due", due)
                .setParameter("taakSoort", taakSoort)
                .executeUpdate();
    }

    public static void zetLeaseTot(EntityManager entityManager, TaakSoort taakSoort, long id, OffsetDateTime tot) {
        entityManager.createQuery("UPDATE Taak SET " + Taak_.LEASE_TOT + " = :tot" + ZELFDE_TAAK)
                .setParameter("tot", tot)
                .setParameter("taakSoort", taakSoort)
                .setParameter("id", id)
                .executeUpdate();
    }

    public static void zetPogingen(EntityManager entityManager, TaakSoort taakSoort, long id, int pogingen) {
        entityManager.createQuery("UPDATE Taak SET " + Taak_.POGINGEN + " = :pogingen" + ZELFDE_TAAK)
                .setParameter("pogingen", pogingen)
                .setParameter("taakSoort", taakSoort)
                .setParameter("id", id)
                .executeUpdate();
    }

    /** Zet de taak op {@code MISLUKT} zonder de pogingen uit te putten. */
    public static void markeerMislukt(EntityManager entityManager, TaakSoort taakSoort, long id) {
        entityManager.createQuery("UPDATE Taak SET " + Taak_.STATUS + " = :mislukt" + ZELFDE_TAAK)
                .setParameter("mislukt", TaakStatus.MISLUKT)
                .setParameter("taakSoort", taakSoort)
                .setParameter("id", id)
                .executeUpdate();
    }

    /** Vergrendelt de taakrij tot het einde van de transactie van de aanroeper. */
    public static void vergrendel(EntityManager entityManager, TaakSoort taakSoort, long id) {
        entityManager.createQuery("FROM Taak" + ZELFDE_TAAK, Taak.class)
                .setParameter("taakSoort", taakSoort)
                .setParameter("id", id)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getSingleResult();
    }

    public static void leegVerzendbudget(EntityManager entityManager) {
        entityManager.createQuery("DELETE FROM VerzendbudgetTijdvak").executeUpdate();
    }

    /** De tokens van het enige tijdvak in de tabel. */
    public static int tokens(EntityManager entityManager) {
        return entityManager.createQuery("SELECT " + VerzendbudgetTijdvak_.TOKENS + " FROM VerzendbudgetTijdvak",
                        Integer.class)
                .getSingleResult();
    }

    public static int tokens(EntityManager entityManager, OffsetDateTime tijdvak) {
        return entityManager.createQuery("SELECT " + VerzendbudgetTijdvak_.TOKENS + " FROM VerzendbudgetTijdvak WHERE "
                        + VerzendbudgetTijdvak_.TIJDVAK + " = :tijdvak", Integer.class)
                .setParameter("tijdvak", tijdvak)
                .getSingleResult();
    }

    public static int telBudgetrijen(EntityManager entityManager) {
        return entityManager.createQuery("SELECT COUNT(*) FROM VerzendbudgetTijdvak", Long.class)
                .getSingleResult()
                .intValue();
    }

    /** Voegt een dienstverlener toe als die er nog niet is. */
    public static void voegDienstverlenerToe(EntityManager entityManager, UUID id, String oin, String naam) {
        entityManager.createQuery("INSERT INTO Dienstverlener (" + Dienstverlener_.ID + ", " + Dienstverlener_.OIN
                        + ", " + Dienstverlener_.NAAM + ") VALUES (:id, :oin, :naam) ON CONFLICT DO NOTHING")
                .setParameter("id", id)
                .setParameter("oin", oin)
                .setParameter("naam", naam)
                .executeUpdate();
    }

    /** Zet het quotum per dag; {@code null} betekent geen quotum. */
    public static void zetQuotumPerDag(EntityManager entityManager, UUID dvId, Integer quotum) {
        entityManager.createQuery("UPDATE Dienstverlener SET " + Dienstverlener_.QUOTUM_PER_DAG + " = :quotum WHERE "
                        + Dienstverlener_.ID + " = :id")
                .setParameter("quotum", quotum)
                .setParameter("id", dvId)
                .executeUpdate();
    }

    /** Zet de webhook in het register; {@code null} betekent geen webhook of de default. */
    public static void zetWebhook(EntityManager entityManager, UUID dvId, String url, Integer maxMislukkingen,
                                  Integer bundel) {
        entityManager.createQuery("UPDATE Dienstverlener SET " + Dienstverlener_.WEBHOOK_URL + " = :url, "
                        + Dienstverlener_.WEBHOOK_MAX_MISLUKKINGEN + " = :max, " + Dienstverlener_.WEBHOOK_BUNDEL
                        + " = :bundel WHERE " + Dienstverlener_.ID + " = :id")
                .setParameter("url", url)
                .setParameter("max", maxMislukkingen)
                .setParameter("bundel", bundel)
                .setParameter("id", dvId)
                .executeUpdate();
    }

    /** Laat een lopende pauze van de webhook op {@code tot} eindigen. */
    public static void beeindigPauze(EntityManager entityManager, UUID dvId, OffsetDateTime tot) {
        entityManager.createQuery("UPDATE Webhookpositie SET " + Webhookpositie_.GEPAUZEERD_TOT + " = :tot WHERE "
                        + Webhookpositie_.DV_ID + " = :dvId AND " + Webhookpositie_.GEPAUZEERD_TOT + " IS NOT NULL")
                .setParameter("tot", tot)
                .setParameter("dvId", dvId)
                .executeUpdate();
    }

    /** Zet de maximale cursorleeftijd in het register; {@code null} is de default uit de configuratie. */
    public static void zetMaxCursorleeftijd(EntityManager entityManager, UUID dvId, Duration leeftijd) {
        // Native: max_cursorleeftijd is niet gemapt op Dienstverlener.
        entityManager.createNativeQuery("UPDATE dienstverlener SET max_cursorleeftijd = CAST(?1 AS bigint) * INTERVAL '1 second' "
                        + "WHERE id = ?2")
                .setParameter(1, leeftijd == null ? null : leeftijd.toSeconds())
                .setParameter(2, dvId)
                .executeUpdate();
    }

    public static void verwijderBevestigingen(EntityManager entityManager) {
        entityManager.createQuery("DELETE FROM Bevestiging").executeUpdate();
    }

    public static void voegBevestigingToe(EntityManager entityManager, UUID dvId, int epoch, long transactieId,
                                          OffsetDateTime bevestigdOp) {
        // Via de Session: Hibernate leidt het type van de transactie-id in een insert niet af, en alleen
        // een Hibernate-query neemt het type als parameter.
        entityManager.unwrap(Session.class).createMutationQuery("INSERT INTO Bevestiging (" + Bevestiging_.DV_ID + ", " + Bevestiging_.EPOCH + ", "
                        + Bevestiging_.TRANSACTIE_ID + ", " + Bevestiging_.EVENT_ID + ", " + Bevestiging_.BEVESTIGD_OP
                        + ") VALUES (:dvId, :epoch, :transactieId, 1, :bevestigdOp)")
                .setParameter("dvId", dvId)
                .setParameter("epoch", epoch)
                .setParameter("transactieId", transactieId, Bevestiging_.transactieId.getType())
                .setParameter("bevestigdOp", bevestigdOp)
                .executeUpdate();
    }

    public static void verwijderWebhookposities(EntityManager entityManager) {
        entityManager.createQuery("DELETE FROM Webhookpositie").executeUpdate();
    }

    /** Een leverpositie met drie mislukkingen, die voor het laatst op {@code geleverdOp} verschoof. */
    public static void voegWebhookpositieToe(EntityManager entityManager, UUID dvId, long transactieId,
                                             OffsetDateTime geleverdOp, OffsetDateTime bijgewerktOp) {
        entityManager.unwrap(Session.class).createMutationQuery("INSERT INTO Webhookpositie (" + Webhookpositie_.DV_ID + ", " + Webhookpositie_.EPOCH
                        + ", " + Webhookpositie_.TRANSACTIE_ID + ", " + Webhookpositie_.EVENT_ID + ", "
                        + Webhookpositie_.MISLUKKINGEN + ", " + Webhookpositie_.BIJGEWERKT_OP + ", "
                        + Webhookpositie_.GELEVERD_OP + ") VALUES (:dvId, 1, :transactieId, 1, 3, :bijgewerktOp, :geleverdOp)")
                .setParameter("dvId", dvId)
                .setParameter("transactieId", transactieId, Webhookpositie_.transactieId.getType())
                .setParameter("bijgewerktOp", bijgewerktOp)
                .setParameter("geleverdOp", geleverdOp)
                .executeUpdate();
    }

    public static OffsetDateTime geleverdOp(EntityManager entityManager, UUID dvId) {
        return entityManager.createQuery("SELECT " + Webhookpositie_.GELEVERD_OP + " FROM Webhookpositie WHERE "
                        + Webhookpositie_.DV_ID + " = :dvId", OffsetDateTime.class)
                .setParameter("dvId", dvId)
                .getSingleResult();
    }

    public static int telDienstverleners(EntityManager entityManager, UUID id) {
        return entityManager.createQuery("SELECT COUNT(*) FROM Dienstverlener WHERE " + Dienstverlener_.ID + " = :id",
                        Long.class)
                .setParameter("id", id)
                .getSingleResult()
                .intValue();
    }
}
