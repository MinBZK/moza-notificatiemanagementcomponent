package nl.rijksoverheid.moz.client.profielservice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PartijResponse {

    public UUID partijId;
    public List<ContactgegevenResponse> contactgegevens;
}
