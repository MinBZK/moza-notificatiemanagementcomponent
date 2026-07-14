package nl.rijksoverheid.moz.nmc.service;

/**
 * Bekende berichttypes en hun bijbehorende NotifyNL-template-IDs.
 */
public enum BerichtType {

    STUURGROEP_AGENDA("Stuurgroep Agenda", "e72c75c5-e74c-4a78-8f2d-c06187a4d51c"),
    DEMO_TEMPLATE("Demo template", "a1f49022-846c-420d-b7f7-dd7b328f1474");

    private final String naam;
    private final String templateId;

    BerichtType(String naam, String templateId) {
        this.naam = naam;
        this.templateId = templateId;
    }

    public String getTemplateId() {
        return templateId;
    }

    public static BerichtType vanNaam(String naam) {
        for (BerichtType type : values()) {
            if (type.naam.equals(naam)) {
                return type;
            }
        }
        throw new OnbekendBerichtTypeException(naam);
    }
}
