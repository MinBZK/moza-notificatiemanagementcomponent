package nl.rijksoverheid.moz.nmc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Bewaakt waarden die op twee plekken staan zonder dat de build een verschil opmerkt. Zonder deze
 * test moest iedereen, mens of AI, onthouden dat ze gelijk moeten blijven.
 * <p>
 * Leest de bronbestanden en niet de gestarte applicatie: een afwijking moet hier falen, ook als
 * Quarkus de ene waarde over de andere heen legt.
 */
@Tag("unit")
class ConfiguratiePariteitTest {

    private static final Path POM = Path.of("pom.xml");
    private static final Path PROPERTIES = Path.of("src/main/resources/application.properties");
    private static final Path CONTRACT = Path.of("src/main/resources/META-INF/openapi.yaml");

    // De gate leest de excludes uit pom.xml, de quarkus-jacoco-extensie uit application.properties.
    @Test
    void jacocoExcludes_inPomEnProperties_zijnGelijk() throws Exception {
        List<String> uitProperties = Arrays.stream(
                        property("%test.quarkus.jacoco.excludes").split(","))
                .map(String::trim)
                .toList();

        assertEquals(jacocoExcludesUitPom(), uitProperties);
    }

    // Excludes op een execution zou de pariteitstest hierboven niet zien.
    @Test
    void jacocoExecutions_inPom_hebbenGeenEigenExcludes() throws Exception {
        assertEquals(List.of(), executionsMetEigenExcludes(POM));
    }

    @Test
    void executionsMetEigenExcludes_vindtAlleenDieMetExcludes(@TempDir Path map) throws Exception {
        Path pom = map.resolve("pom.xml");
        Files.writeString(pom, """
                <project><build><plugins><plugin>
                  <artifactId>jacoco-maven-plugin</artifactId>
                  <configuration><excludes><exclude>**/api/**</exclude></excludes></configuration>
                  <executions>
                    <execution><id>prepare-agent</id></execution>
                    <execution><id>report</id><configuration><formats/></configuration></execution>
                    <execution><id>check</id><configuration><excludes><exclude>x</exclude></excludes></configuration></execution>
                  </executions>
                </plugin></plugins></build></project>
                """);

        assertEquals(List.of("check"), executionsMetEigenExcludes(pom));
    }

    @Test
    void executionsMetEigenExcludes_zonderExecutions_isLeeg(@TempDir Path map) throws Exception {
        Path pom = map.resolve("pom.xml");
        Files.writeString(pom, """
                <project><build><plugins><plugin>
                  <artifactId>jacoco-maven-plugin</artifactId>
                </plugin></plugins></build></project>
                """);

        assertEquals(List.of(), executionsMetEigenExcludes(pom));
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
        Element configuratie = kind(jacocoPlugin(POM), "configuration");
        assertNotNull(configuratie, "jacoco-maven-plugin heeft geen configuration op pluginniveau");
        NodeList excludes = configuratie.getElementsByTagName("exclude");
        List<String> resultaat = new ArrayList<>();

        for (int j = 0; j < excludes.getLength(); j++) {
            resultaat.add(excludes.item(j).getTextContent().trim());
        }

        assertFalse(resultaat.isEmpty(), "jacoco-maven-plugin heeft geen excludes");

        return resultaat;
    }

    /**
     * De id's van de executions van de jacoco-maven-plugin met eigen {@code excludes}. Die vervangen
     * de excludes op pluginniveau, en die vergelijkt de pariteitstest alleen.
     */
    private static List<String> executionsMetEigenExcludes(Path pom) throws Exception {
        List<String> resultaat = new ArrayList<>();
        Element executions = kind(jacocoPlugin(pom), "executions");

        if (executions == null) {
            return resultaat;
        }

        NodeList kinderen = executions.getElementsByTagName("execution");

        for (int i = 0; i < kinderen.getLength(); i++) {
            Element execution = (Element) kinderen.item(i);
            Element configuratie = kind(execution, "configuration");

            if (configuratie != null && kind(configuratie, "excludes") != null) {
                resultaat.add(kindTekst(execution, "id"));
            }
        }

        return resultaat;
    }

    private static Element jacocoPlugin(Path pom) throws Exception {
        Document document =
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
        NodeList plugins = document.getElementsByTagName("plugin");

        for (int i = 0; i < plugins.getLength(); i++) {
            Element plugin = (Element) plugins.item(i);

            if ("jacoco-maven-plugin".equals(kindTekst(plugin, "artifactId"))) {
                return plugin;
            }
        }

        throw new AssertionError("jacoco-maven-plugin niet gevonden in " + pom);
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
                return regel.substring(regel.indexOf(':') + 1)
                        .trim()
                        .replace("\"", "")
                        .replace("'", "");
            }
        }

        throw new AssertionError("info.version niet gevonden in " + CONTRACT);
    }

    private static Element kind(Element ouder, String naam) {
        NodeList kinderen = ouder.getChildNodes();

        for (int i = 0; i < kinderen.getLength(); i++) {
            if (kinderen.item(i) instanceof Element element
                    && element.getTagName().equals(naam)) {
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
