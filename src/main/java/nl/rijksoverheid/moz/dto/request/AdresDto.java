package nl.rijksoverheid.moz.dto.request;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Postadres van de ontvanger, gebruikt bij fysieke verzending (contactherstel)")
public class AdresDto {

    @NotBlank
    public String straat;

    @NotBlank
    public String huisnummer;

    @Nullable
    public String huisnummerToevoeging;

    @NotBlank
    public String postcode;

    @NotBlank
    public String plaats;

    @NotBlank
    public String land;
}
