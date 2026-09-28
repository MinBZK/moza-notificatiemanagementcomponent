package nl.rijksoverheid.moz.nmc.domain;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.service.Overgangsfunctie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Toetst de databasetrigger op {@code notificatie} met SQL buiten de applicatie om, zoals een script of
 * een tweede schrijver dat zou doen. De trigger is deferred en gaat dus pas bij de commit af.
 */
@QuarkusTest
class NotificatieOvergangTriggerTest {

    @Inject
    EntityManager entityManager;

    @Inject
    Overgangsfunctie overgangsfunctie;

    @Inject
    NotificatieRepository notificatieRepository;

    @Inject
    EventRepository eventRepository;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            eventRepository.deleteAll();
            notificatieRepository.deleteAll();
        });
    }

    @Test
    void nieuweVersieZonderEvent_wordtGeweigerd() {
        UUID id = aangenomenNotificatie();

        Throwable fout = assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                sql("UPDATE notificatie SET versie = 1, status = 'IN_VERZENDING' WHERE id = ?1", id)));

        assertTrue(bevat(fout, "heeft geen event"), fout.toString());
    }

    @Test
    void nieuweVersieMetEvent_wordtGeaccepteerd() {
        UUID id = aangenomenNotificatie();

        assertDoesNotThrow(() -> QuarkusTransaction.requiringNew().run(() -> {
            sql("UPDATE notificatie SET versie = 1, status = 'IN_VERZENDING' WHERE id = ?1", id);
            schrijfEvent(id, 1, "AANGENOMEN", "IN_VERZENDING");
        }));
    }

    @Test
    void nietToegestaneOvergang_wordtGeweigerdOokMetEvent() {
        UUID id = aangenomenNotificatie();

        Throwable fout = assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            sql("UPDATE notificatie SET versie = 1, status = 'BEZORGD' WHERE id = ?1", id);
            schrijfEvent(id, 1, "AANGENOMEN", "BEZORGD");
        }));

        assertTrue(bevat(fout, "niet toegestaan"), fout.toString());
    }

    @Test
    void statuswijzigingZonderNieuweVersie_wordtGeweigerd() {
        UUID id = aangenomenNotificatie();

        Throwable fout = assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                sql("UPDATE notificatie SET status = 'IN_VERZENDING' WHERE id = ?1", id)));

        assertTrue(bevat(fout, "zonder nieuwe versie"), fout.toString());
    }

    // Het event moet in dezelfde transactie staan; een event met het juiste volgnummer uit een eerdere
    // transactie telt niet.
    @Test
    void eventUitEenEerdereTransactie_teltNiet() {
        UUID id = aangenomenNotificatie();
        QuarkusTransaction.requiringNew().run(() -> schrijfEvent(id, 1, "AANGENOMEN", "IN_VERZENDING"));

        Throwable fout = assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                sql("UPDATE notificatie SET versie = 1, status = 'IN_VERZENDING' WHERE id = ?1", id)));

        assertTrue(bevat(fout, "heeft geen event"), fout.toString());
    }

    @Test
    void nieuweNotificatieNietOpAangenomen_wordtGeweigerd() {
        UUID id = UUID.randomUUID();

        Throwable fout = assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            sql("INSERT INTO notificatie (id, versie, status, laatste_status_update) "
                    + "VALUES (?1, 0, 'VERZONDEN', CURRENT_TIMESTAMP)", id);
            schrijfEvent(id, 0, null, "VERZONDEN");
        }));

        assertTrue(bevat(fout, "niet op AANGENOMEN"), fout.toString());
    }

    @Test
    void nieuweNotificatieZonderEvent_wordtGeweigerd() {
        UUID id = UUID.randomUUID();

        Throwable fout = assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                sql("INSERT INTO notificatie (id, versie, status, laatste_status_update) "
                        + "VALUES (?1, 0, 'AANGENOMEN', CURRENT_TIMESTAMP)", id)));

        assertTrue(bevat(fout, "heeft geen event"), fout.toString());
    }

    private UUID aangenomenNotificatie() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(null);
            overgangsfunctie.neemAan(notificatie);

            return notificatie.getId();
        });
    }

    private void sql(String sql, UUID id) {
        entityManager.createNativeQuery(sql).setParameter(1, id).executeUpdate();
    }

    private void schrijfEvent(UUID id, long volgnummer, String van, String naar) {
        entityManager.createNativeQuery("INSERT INTO event (tijdstip, notificatie_id, volgnummer, van, naar) "
                        + "VALUES (CURRENT_TIMESTAMP, ?1, ?2, ?3, ?4)")
                .setParameter(1, id)
                .setParameter(2, volgnummer)
                .setParameter(3, van)
                .setParameter(4, naar)
                .executeUpdate();
    }

    // De databasefout zit als suppressed exception onder de RollbackException van de commit.
    private static boolean bevat(Throwable fout, String tekst) {
        if (fout == null) {
            return false;
        }

        if (fout.getMessage() != null && fout.getMessage().contains(tekst)) {
            return true;
        }

        for (Throwable onderdrukt : fout.getSuppressed()) {
            if (bevat(onderdrukt, tekst)) {
                return true;
            }
        }

        return fout.getCause() != fout && bevat(fout.getCause(), tekst);
    }
}
