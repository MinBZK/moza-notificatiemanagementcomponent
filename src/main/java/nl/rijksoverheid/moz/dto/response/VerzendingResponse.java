package nl.rijksoverheid.moz.dto.response;

import jakarta.annotation.Nullable;
import nl.rijksoverheid.moz.common.NotificatieStatus;
import nl.rijksoverheid.moz.common.VerzendKanaal;
import nl.rijksoverheid.moz.common.VerzendingType;

import java.time.Instant;
import java.util.UUID;

public class VerzendingResponse {
    public UUID id;
    public VerzendingType type;
    public VerzendKanaal kanaal;
    public NotificatieStatus status;

    @Nullable
    public UUID notifyReferentie;

    public Instant aangemaaktAt;

    @Nullable
    public Instant verzondenAt;

    @Nullable
    public Instant afgerondAt;
}
