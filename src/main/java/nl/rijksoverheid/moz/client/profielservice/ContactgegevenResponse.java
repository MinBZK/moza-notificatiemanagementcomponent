package nl.rijksoverheid.moz.client.profielservice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import nl.rijksoverheid.moz.common.ContactType;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ContactgegevenResponse {

    public ContactType type;
    public String waarde;
    public boolean isDefault;
}
