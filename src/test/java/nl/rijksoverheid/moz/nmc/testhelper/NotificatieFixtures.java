package nl.rijksoverheid.moz.nmc.testhelper;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Schrijft rijen buiten Hibernate om, voor tests die juist een toestand willen die Notificatie zelf
 * niet kan maken. Staat op één plek zodat een kolomwijziging niet door meerdere testklassen hoeft.
 */
public final class NotificatieFixtures {

    private NotificatieFixtures() {
    }

    /** Een notificatie met één statusregel op volgnummer 0, zoals de V2-backfill hem achterlaat. */
    public static void voegNotificatieMetEenStatusregelToe(EntityManager entityManager, UUID id, UUID externalReference,
                                                           StatusWaarde status, OffsetDateTime tijdstip) {
        voegNotificatieToe(entityManager, id, externalReference, null,
                List.of(NotificatieStatus.opEigenKlok(status, tijdstip)));
    }

    /**
     * Een notificatie met een bewust terug- of vooruitgedateerde geschiedenis, die
     * {@code Notificatie#registreerStatus} niet kan maken: die stempelt altijd de eigen klok van nu.
     * De kopie op {@code notificatie} volgt het laatste record, net als in productie.
     */
    public static void voegNotificatieToe(EntityManager entityManager, UUID id, UUID externalReference,
                                          String callbackUrl, List<NotificatieStatus> geschiedenis) {
        NotificatieStatus laatste = geschiedenis.getLast();
        entityManager.createNativeQuery("INSERT INTO notificatie (id, versie, external_reference, callback_url, "
                        + "laatste_status, laatste_status_update) VALUES (?1, 0, ?2, ?3, ?4, ?5)")
                .setParameter(1, id)
                .setParameter(2, externalReference)
                .setParameter(3, callbackUrl)
                .setParameter(4, laatste.status().name())
                .setParameter(5, laatste.geregistreerd())
                .executeUpdate();

        for (int volgnummer = 0; volgnummer < geschiedenis.size(); volgnummer++) {
            NotificatieStatus record = geschiedenis.get(volgnummer);
            voegStatusregelToe(entityManager, id, volgnummer, record.status(), record.tijdstip());
        }
    }

    public static int voegStatusregelToe(EntityManager entityManager, UUID notificatieId, int volgnummer,
                                         StatusWaarde status, OffsetDateTime tijdstip) {
        return entityManager.createNativeQuery("INSERT INTO notificatie_status (notificatie_id, volgnummer, status, "
                        + "tijdstip, geregistreerd) VALUES (?1, ?2, ?3, ?4, ?4)")
                .setParameter(1, notificatieId)
                .setParameter(2, volgnummer)
                .setParameter(3, status.name())
                .setParameter(4, tijdstip)
                .executeUpdate();
    }

    public static void verwijderStatusregel(EntityManager entityManager, UUID notificatieId, int volgnummer) {
        entityManager.createNativeQuery("DELETE FROM notificatie_status WHERE notificatie_id = ?1 AND volgnummer = ?2")
                .setParameter(1, notificatieId)
                .setParameter(2, volgnummer)
                .executeUpdate();
    }

    public static void verwijderNotificatie(EntityManager entityManager, UUID id) {
        entityManager.createNativeQuery("DELETE FROM notificatie WHERE id = ?1")
                .setParameter(1, id)
                .executeUpdate();
    }

    /** Zet de registratietijd terug, zodat een notificatie buiten de bewaartermijn valt. */
    public static void verzetLaatsteStatusUpdate(EntityManager entityManager, UUID id, OffsetDateTime tijdstip) {
        entityManager.createNativeQuery("UPDATE notificatie SET laatste_status_update = ?1 WHERE id = ?2")
                .setParameter(1, tijdstip)
                .setParameter(2, id)
                .executeUpdate();
    }

    public static long telStatusregels(EntityManager entityManager, UUID notificatieId) {
        return ((Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM notificatie_status "
                        + "WHERE notificatie_id = ?1")
                .setParameter(1, notificatieId)
                .getSingleResult()).longValue();
    }

    /**
     * Plant in één statement {@code aantalRijen} notificaties met dezelfde statusreeks, voor tests die
     * meer rijen nodig hebben dan een batch groot is.
     * <p>
     * H2-specifiek: {@code SYSTEM_RANGE} levert de rijen en het id wordt uit het rijnummer afgeleid,
     * zodat beide tabellen naar hetzelfde id verwijzen. De laatst meegegeven status geldt als de
     * huidige, want deze fixture slaat {@code Notificatie#registreerStatus} bewust over.
     */
    public static void plantNotificaties(EntityManager entityManager, int aantalRijen, OffsetDateTime tijdstip,
                                         StatusWaarde... statussen) {
        String idExpressie = "CAST(('00000000-0000-0000-0000-' || LPAD(CAST(X AS VARCHAR), 12, '0')) AS UUID)";
        StatusWaarde laatsteStatus = statussen[statussen.length - 1];
        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery("INSERT INTO notificatie (id, laatste_status, laatste_status_update) "
                            + "SELECT " + idExpressie + ", ?3, ?1 FROM SYSTEM_RANGE(1, ?2)")
                    .setParameter(1, tijdstip)
                    .setParameter(2, aantalRijen)
                    .setParameter(3, laatsteStatus.name())
                    .executeUpdate();
            for (int volgnummer = 0; volgnummer < statussen.length; volgnummer++) {
                entityManager.createNativeQuery("INSERT INTO notificatie_status (notificatie_id, volgnummer, status, "
                                + "tijdstip, geregistreerd) SELECT " + idExpressie + ", ?3, ?4, ?1, ?1 "
                                + "FROM SYSTEM_RANGE(1, ?2)")
                        .setParameter(1, tijdstip)
                        .setParameter(2, aantalRijen)
                        .setParameter(3, volgnummer)
                        .setParameter(4, statussen[volgnummer].name())
                        .executeUpdate();
            }
        });
    }
}
