package nl.rijksoverheid.moz.dto.request;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import nl.rijksoverheid.moz.common.VerzendKanaal;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Aanvraag om contactherstel te initiëren voor een bestaande notificatie")
public class ContactherstelAanvraagRequest {

    @NotNull
    @Schema(description = "Verzendkanaal voor de contactherstelpoging")
    public VerzendKanaal kanaal;

    @Nullable
    @Schema(description = "E-mailadres van de ontvanger, bij kanaal=EMAIL")
    public String ontvangerEmail;

    @Nullable
    @Valid
    @Schema(description = "Postadres van de ontvanger, bij kanaal=FYSIEK")
    public AdresDto ontvangerAdres;
}
