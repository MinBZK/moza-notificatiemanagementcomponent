package nl.rijksoverheid.moz.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Delivery receipt callback van NotifyNL")
public class DeliveryReceiptRequest {

    @NotNull
    @Schema(description = "Notify-referentie van de notificatie")
    public UUID id;

    @Nullable
    public String reference;

    @Nullable
    public String to;

    @NotNull
    @Schema(description = "Statuswaarde zoals door Notify aangeleverd")
    public String status;

    @JsonProperty("created_at")
    @Nullable
    public Instant createdAt;

    @JsonProperty("completed_at")
    @Nullable
    public Instant completedAt;

    @JsonProperty("sent_at")
    @Nullable
    public Instant sentAt;

    @JsonProperty("notification_type")
    @Nullable
    public String notificationType;

    @JsonProperty("template_id")
    @Nullable
    public UUID templateId;

    @JsonProperty("template_version")
    @Nullable
    public Integer templateVersion;
}
