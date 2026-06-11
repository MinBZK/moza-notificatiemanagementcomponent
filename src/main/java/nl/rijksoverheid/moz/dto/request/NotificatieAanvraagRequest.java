package nl.rijksoverheid.moz.dto.request;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import nl.rijksoverheid.moz.common.IdentificatieType;
import nl.rijksoverheid.moz.common.ProfielType;
import nl.rijksoverheid.moz.common.VerzendKanaal;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Map;

@Schema(description = "Aanvraag om een notificatie te versturen")
public class NotificatieAanvraagRequest {

    @NotNull
    @Schema(description = "Type identificatie van de ontvanger")
    public IdentificatieType identificatieType;

    @NotBlank
    @Schema(description = "BSN-, KVK- of RSIN-nummer van de ontvanger")
    public String identificatieNummer;

    @NotBlank
    @Schema(description = "Naam van de dienstverlener namens wie de notificatie wordt verstuurd")
    public String dienstverlener;

    @NotBlank
    @Schema(description = "Naam van de dienst waarvoor de notificatie wordt verstuurd")
    public String dienst;

    @NotBlank
    @Schema(description = "Berichttype, gebruikt om de juiste template op te halen")
    public String berichtType;

    @Nullable
    @Schema(description = "Templatevariabelen voor het bericht")
    public Map<String, String> berichtgegevens;

    @NotNull
    @Schema(description = "Centraal: NMC bepaalt zelf de contactgegevens. Decentraal: contactgegevens worden meegegeven door de aanroeper.")
    public ProfielType profielType;

    @Nullable
    @Schema(description = "Verzendkanaal, verplicht bij profielType=DECENTRAAL")
    public VerzendKanaal verzendKanaal;

    @Nullable
    @Schema(description = "E-mailadres van de ontvanger, bij profielType=DECENTRAAL en verzendKanaal=EMAIL")
    public String ontvangerEmail;

    @Nullable
    @Valid
    @Schema(description = "Postadres van de ontvanger, bij profielType=DECENTRAAL en verzendKanaal=FYSIEK")
    public AdresDto ontvangerAdres;

    @Nullable
    @Schema(description = "Correlatie-referentie van de aanroepende partij (bijvoorbeeld een OMC)")
    public String referentie;
}
