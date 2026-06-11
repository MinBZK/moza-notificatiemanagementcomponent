package nl.rijksoverheid.moz.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.common.NotificatieStatus;
import nl.rijksoverheid.moz.dto.request.AdresDto;
import nl.rijksoverheid.moz.dto.response.NotificatieResponse;
import nl.rijksoverheid.moz.dto.response.VerzendingResponse;
import nl.rijksoverheid.moz.entity.Adres;
import nl.rijksoverheid.moz.entity.Notificatie;
import nl.rijksoverheid.moz.entity.Verzending;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;

@ApplicationScoped
public class NotificatieMapper {

    private final ObjectMapper objectMapper;

    public NotificatieMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NotificatieResponse naarResponse(Notificatie notificatie) {
        NotificatieResponse response = new NotificatieResponse();
        response.id = notificatie.id;
        response.profielType = notificatie.getProfielType();
        response.identificatieType = notificatie.getIdentificatieType();
        response.identificatieNummer = notificatie.getIdentificatieNummer();
        response.dienstverlener = notificatie.getDienstverlener();
        response.dienst = notificatie.getDienst();
        response.berichtType = notificatie.getBerichtType();
        response.aangemaaktAt = notificatie.getAangemaaktAt();
        response.verzendingen = notificatie.getVerzendingen().stream()
                .map(this::naarVerzendingResponse)
                .toList();
        response.status = notificatie.getVerzendingen().stream()
                .max(Comparator.comparing(Verzending::getAangemaaktAt))
                .map(Verzending::getStatus)
                .orElse(null);

        return response;
    }

    public VerzendingResponse naarVerzendingResponse(Verzending verzending) {
        VerzendingResponse response = new VerzendingResponse();
        response.id = verzending.id;
        response.type = verzending.getType();
        response.kanaal = verzending.getKanaal();
        response.status = verzending.getStatus();
        response.notifyReferentie = verzending.getNotifyReferentie();
        response.aangemaaktAt = verzending.getAangemaaktAt();
        response.verzondenAt = verzending.getVerzondenAt();
        response.afgerondAt = verzending.getAfgerondAt();

        return response;
    }

    @Nullable
    public Adres naarAdres(@Nullable AdresDto dto) {
        if (dto == null) {
            return null;
        }

        Adres adres = new Adres();
        adres.setStraat(dto.straat);
        adres.setHuisnummer(dto.huisnummer);
        adres.setHuisnummerToevoeging(dto.huisnummerToevoeging);
        adres.setPostcode(dto.postcode);
        adres.setPlaats(dto.plaats);
        adres.setLand(dto.land);

        return adres;
    }

    @Nullable
    public String serialiseer(@Nullable Map<String, String> berichtgegevens) {
        if (berichtgegevens == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(berichtgegevens);
        } catch (JsonProcessingException e) {
            throw new WebApplicationException("Kan berichtgegevens niet serialiseren", e, Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    public NotificatieStatus naarNotificatieStatus(String ruweStatus) {
        String genormaliseerd = ruweStatus.toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return NotificatieStatus.valueOf(genormaliseerd);
        } catch (IllegalArgumentException e) {
            return NotificatieStatus.TECHNICAL_FAILURE;
        }
    }
}
