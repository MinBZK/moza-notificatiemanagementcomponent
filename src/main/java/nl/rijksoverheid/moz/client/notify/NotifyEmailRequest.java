package nl.rijksoverheid.moz.client.notify;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public class NotifyEmailRequest {

    @JsonProperty("email_address")
    public String emailAddress;

    @JsonProperty("template_id")
    public String templateId;

    public Map<String, String> personalisation;

    public NotifyEmailRequest(String emailAddress, String templateId, Map<String, String> personalisation) {
        this.emailAddress = emailAddress;
        this.templateId = templateId;
        this.personalisation = personalisation;
    }
}
