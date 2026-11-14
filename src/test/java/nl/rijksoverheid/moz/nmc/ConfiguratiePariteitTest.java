package nl.rijksoverheid.moz.nmc;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Bewaakt waarden die op twee plekken staan zonder dat de build een verschil opmerkt. Zonder deze
 * test moest iedereen, mens of AI, onthouden dat ze gelijk moeten blijven.
 * <p>
 * Leest de bronbestanden en niet de gestarte applicatie: een afwijking moet hier falen, ook als
 * Quarkus de ene waarde over de andere heen legt.
 */
class ConfiguratiePariteitTest {

    private static final Path POM = Path.of("pom.xml");
    private static final Path PROPERTIES = Path.of("src/main/resources/application.properties");
    private static final Path CONTRACT = Path.of("src/main/resources/META-INF/openapi.yaml");

    // De gate leest de excludes uit pom.xml, de quarkus-jacoco-extensie uit application.properties.
    @Test
    void jacocoExcludes_inPomEnProperties_zijnGelijk() throws Exception {
        List<String> uitProperties = Arrays.stream(property("%test.quarkus.jacoco.excludes").split(","))
                .map(String::trim)
                .toList();

        assertEquals(jacocoExcludesUitPom(), uitProperties);
    }

    // ApiVersionFilter stuurt de waarde uit application.properties mee als API-Version-header.
    @Test
    void contractversie_inContractEnProperties_isGelijk() throws Exception {
        assertEquals(infoVersionUitContract(), property("mp.openapi.info.version"));
    }

    private static String property(String sleutel) throws IOException {
        Properties properties = new Properties();

        try (Reader reader = Files.newBufferedReader(PROPERTIES)) {
            properties.load(reader);
        }

        String waarde = properties.getProperty(sleutel);
        assertNotNull(waarde, sleutel + " ontbreekt in " + PROPERTIES);

        return waarde.trim();
    }

    private static List<String> jacocoExcludesUitPom() throws Exception {
        Document pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(POM.toFile());
        NodeList plugins = pom.getElementsByTagName("plugin");

        for (int i = 0; i < plugins.getLength(); i++) {
            Element plugin = (Element) plugins.item(i);

            if (!"jacoco-maven-plugin".equals(kindTekst(plugin, "artifactId"))) {
                continue;
            }

            Element configuratie = kind(plugin, "configuration");
            assertNotNull(configuratie, "jacoco-maven-plugin heeft geen configuration op pluginniveau");
            NodeList excludes = configuratie.getElementsByTagName("exclude");
            List<String> resultaat = new ArrayList<>();

            for (int j = 0; j < excludes.getLength(); j++) {
                resultaat.add(excludes.item(j).getTextContent().trim());
            }

            assertFalse(resultaat.isEmpty(), "jacoco-maven-plugin heeft geen excludes");

            return resultaat;
        }

        throw new AssertionError("jacoco-maven-plugin niet gevonden in " + POM);
    }

    // Het eerste ingesprongen version-veld onder het topniveau-blok info.
    private static String infoVersionUitContract() throws IOException {
        boolean inInfo = false;

        for (String regel : Files.readAllLines(CONTRACT)) {
            if (regel.equals("info:")) {
                inInfo = true;
                continue;
            }

            if (inInfo && !regel.isBlank() && !Character.isWhitespace(regel.charAt(0))) {
                break;
            }

            if (inInfo && regel.matches("^ {2}version:.*")) {
                return regel.substring(regel.indexOf(':') + 1).trim().replace("\"", "").replace("'", "");
            }
        }

        throw new AssertionError("info.version niet gevonden in " + CONTRACT);
    }

    private static Element kind(Element ouder, String naam) {
        NodeList kinderen = ouder.getChildNodes();

        for (int i = 0; i < kinderen.getLength(); i++) {
            if (kinderen.item(i) instanceof Element element && element.getTagName().equals(naam)) {
                return element;
            }
        }

        return null;
    }

    private static String kindTekst(Element ouder, String naam) {
        Element element = kind(ouder, naam);

        return element == null ? null : element.getTextContent().trim();
    }
}
