package nl.rijksoverheid.moz.client.notify;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class NotifyEmailResponse {

    public UUID id;
}
