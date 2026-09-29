package nl.rijksoverheid.moz.nmc.testhelper;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Schrijft rijen buiten Hibernate en buiten de overgangsfunctie om, voor tests die een toestand
 * willen die de applicatie zelf niet maakt: een willekeurige status met een teruggedateerd
 * {@code terminaal_op}. Staat op één plek zodat een kolomwijziging niet door meerdere
 * testklassen hoeft.
 * <p>
 * Elke methode verwacht een lopende transactie. De constraint trigger op {@code notificatie} eist een
 * event per versie; de inserts hier zetten hem voor de eigen transactie uit.
 */
public final class NotificatieFixtures {

    /** De dienstverlener uit migratie V8 en {@code nmc.dienstverlener.id} onder {@code %test}. */
    public static final UUID DV_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private NotificatieFixtures() {
    }

    /**
     * Een notificatie in de gegeven status, zonder poging.
     *
     * @param terminaalOp leeg voor een niet-terminale status
     */
    public static void voegNotificatieToe(EntityManager entityManager, UUID id, NotificatieStatus status,
                                          OffsetDateTime terminaalOp) {
        zetTriggerUit(entityManager);
        entityManager.createNativeQuery("INSERT INTO notificatie (id, dv_id, versie, status, terminaal_op) "
                        + "VALUES (?1, ?2, 0, ?3, CAST(?4 AS timestamptz))")
                .setParameter(1, id)
                .setParameter(2, DV_ID)
                .setParameter(3, status.name())
                .setParameter(4, terminaalOp)
                .executeUpdate();
    }

    /** Een poging bij een bestaande notificatie, met het NotifyNL-id waarop een receipt binnenkomt. */
    public static void voegPogingToe(EntityManager entityManager, UUID notificatieId, int nummer, UUID notifyId,
                                     PogingStatus status, OffsetDateTime verzondenOp) {
        entityManager.createNativeQuery("INSERT INTO poging (id, notificatie_id, nummer, status, notify_id, verzonden_op) "
                        + "VALUES (?1, ?2, ?3, ?4, ?5, ?6)")
                .setParameter(1, UUID.randomUUID())
                .setParameter(2, notificatieId)
                .setParameter(3, nummer)
                .setParameter(4, status.name())
                .setParameter(5, notifyId)
                .setParameter(6, verzondenOp)
                .executeUpdate();
    }

    /** Een event met een eigen tijdstip, los van de registratietijd op de notificatie. */
    public static void voegEventToe(EntityManager entityManager, UUID notificatieId, long volgnummer,
                                    NotificatieStatus van, NotificatieStatus naar, OffsetDateTime tijdstip) {
        entityManager.createNativeQuery("INSERT INTO event (tijdstip, dv_id, notificatie_id, volgnummer, van, naar) "
                        + "VALUES (?1, ?6, ?2, ?3, ?4, ?5)")
                .setParameter(1, tijdstip)
                .setParameter(6, DV_ID)
                .setParameter(2, notificatieId)
                .setParameter(3, volgnummer)
                .setParameter(4, van != null ? van.name() : null)
                .setParameter(5, naar.name())
                .executeUpdate();
    }

    public static void verwijderNotificatie(EntityManager entityManager, UUID id) {
        entityManager.createNativeQuery("DELETE FROM notificatie WHERE id = ?1")
                .setParameter(1, id)
                .executeUpdate();
    }

    /** Zet het moment van de terminale status terug, zodat wistermijn of bewaartermijn verstreken is. */
    public static void verzetTerminaalOp(EntityManager entityManager, UUID id, OffsetDateTime tijdstip) {
        entityManager.createNativeQuery("UPDATE notificatie SET terminaal_op = ?1 WHERE id = ?2")
                .setParameter(1, tijdstip)
                .setParameter(2, id)
                .executeUpdate();
    }

    public static long telPogingen(EntityManager entityManager, UUID notificatieId) {
        return ((Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM poging WHERE notificatie_id = ?1")
                .setParameter(1, notificatieId)
                .getSingleResult()).longValue();
    }

    /**
     * Plant in één statement {@code aantalRijen} notificaties in dezelfde status, met per notificatie
     * {@code aantalPogingen} pogingen, voor tests die meer rijen nodig hebben dan een batch groot is.
     * {@code generate_series} levert de rijen en het id wordt uit het rijnummer afgeleid, zodat de
     * pogingen naar hetzelfde id verwijzen.
     */
    public static void plantNotificaties(EntityManager entityManager, int aantalRijen, OffsetDateTime terminaalOp,
                                         NotificatieStatus status, int aantalPogingen) {
        String idExpressie = "('00000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid";
        QuarkusTransaction.requiringNew().run(() -> {
            zetTriggerUit(entityManager);
            entityManager.createNativeQuery("INSERT INTO notificatie (id, dv_id, versie, status, terminaal_op) "
                            + "SELECT " + idExpressie + ", ?4, 0, ?3, ?1 FROM generate_series(1, ?2) AS g")
                    .setParameter(1, terminaalOp)
                    .setParameter(4, DV_ID)
                    .setParameter(2, aantalRijen)
                    .setParameter(3, status.name())
                    .executeUpdate();

            for (int nummer = 1; nummer <= aantalPogingen; nummer++) {
                entityManager.createNativeQuery("INSERT INTO poging (id, notificatie_id, nummer, status, notify_id, verzonden_op) "
                                + "SELECT gen_random_uuid(), " + idExpressie + ", ?3, ?4, gen_random_uuid(), ?1 "
                                + "FROM generate_series(1, ?2) AS g")
                        .setParameter(1, terminaalOp)
                        .setParameter(2, aantalRijen)
                        .setParameter(3, nummer)
                        .setParameter(4, PogingStatus.VERZONDEN.name())
                        .executeUpdate();
            }
        });
    }

    // Alleen voor de lopende transactie; een insert met een andere status dan AANGENOMEN en zonder
    // event komt anders niet door de constraint trigger.
    private static void zetTriggerUit(EntityManager entityManager) {
        entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
    }
}
