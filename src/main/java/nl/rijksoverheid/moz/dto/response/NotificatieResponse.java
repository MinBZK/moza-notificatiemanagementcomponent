package nl.rijksoverheid.moz.dto.response;

import nl.rijksoverheid.moz.common.IdentificatieType;
import nl.rijksoverheid.moz.common.NotificatieStatus;
import nl.rijksoverheid.moz.common.ProfielType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class NotificatieResponse {
    public UUID id;
    public ProfielType profielType;
    public IdentificatieType identificatieType;
    public String identificatieNummer;
    public String dienstverlener;
    public String dienst;
    public String berichtType;
    public NotificatieStatus status;
    public Instant aangemaaktAt;
    public List<VerzendingResponse> verzendingen;
}
