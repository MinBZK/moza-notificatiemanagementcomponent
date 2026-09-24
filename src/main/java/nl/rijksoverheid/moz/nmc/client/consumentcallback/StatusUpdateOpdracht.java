package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Reden;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Opdracht om een statusovergang naar de Dienstverlener te sturen, afgevuurd na een overgang en pas
 * na de commit uitgevoerd door StatusUpdateVerzender. Bevat platte waarden: de observer draait
 * buiten de transactie waarin de entities geladen zijn.
 *
 * @param versie   het volgnummer van de overgang; de Dienstverlener ordent daarop
 * @param tijdstip wanneer de overgang is vastgelegd, op de klok van de NMC
 */
public record StatusUpdateOpdracht(UUID notificatieId, String callbackUrl, long versie,
                                   NotificatieStatus van, NotificatieStatus naar, Reden reden,
                                   OffsetDateTime tijdstip) {

    public StatusUpdateOpdracht {
        // Hier controleren en niet pas bij het versturen: in de AFTER_SUCCESS-observer zou een NPE ná
        // de commit afgaan, waar de transactiemanager hem opslokt.
        Objects.requireNonNull(notificatieId, "notificatieId is verplicht");
        Objects.requireNonNull(naar, "naar is verplicht");
        Objects.requireNonNull(tijdstip, "tijdstip is verplicht");
        // callbackUrl mag bewust null zijn: de Dienstverlener heeft dan geen callback geconfigureerd.
    }

    public static StatusUpdateOpdracht van(Event event, String callbackUrl) {
        return new StatusUpdateOpdracht(event.getNotificatieId(), callbackUrl, event.getVolgnummer(),
                event.getVan(), event.getNaar(), event.getReden(), event.getTijdstip());
    }
}
