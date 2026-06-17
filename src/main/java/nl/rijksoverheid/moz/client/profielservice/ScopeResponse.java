package nl.rijksoverheid.moz.client.profielservice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ScopeResponse {

    public String dienstverlenerNaam;
    public String dienstNaam;
}
