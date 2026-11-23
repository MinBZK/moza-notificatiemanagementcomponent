package nl.rijksoverheid.moz.nmc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bewaakt dat elke testklasse óf een {@code @QuarkusTest} is, óf de tag {@code unit} heeft, óf
 * hieronder met reden is uitgezonderd. Mutation testing (PIT) draait alleen de tests met de tag
 * {@code unit}; een unittest zonder tag valt er anders stil buiten.
 */
@Tag("unit")
class TestindelingTest {

    private static final Path TESTBRONNEN = Path.of("src/test/java");
    private static final Pattern TESTMETHODE =
            Pattern.compile("@(Test|ParameterizedTest|RepeatedTest|TestFactory|FuzzTest)\\b");
    // Aan het begin van een regel, zodat een vermelding in commentaar of een string niet meetelt.
    private static final Pattern QUARKUS = Pattern.compile("^@QuarkusTest\\b", Pattern.MULTILINE);
    private static final Pattern UNIT = Pattern.compile("^@Tag\\(\"unit\"\\)", Pattern.MULTILINE);

    // Zonder Quarkus, maar ongeschikt voor PIT: ze toetsen SQL of een shellscript, geen Java-code.
    private static final Map<String, String> UITGEZONDERD = Map.of(
            "nl/rijksoverheid/moz/nmc/VerifyVoorPushHookTest.java", "toetst de push-hook, een shellscript",
            "nl/rijksoverheid/moz/nmc/migratie/V2MigratieTest.java", "eigen embedded PostgreSQL, toetst een migratie",
            "nl/rijksoverheid/moz/nmc/migratie/V5MigratieTest.java", "eigen embedded PostgreSQL, toetst een migratie",
            "nl/rijksoverheid/moz/nmc/migratie/V14MigratieTest.java", "eigen embedded PostgreSQL, toetst een migratie",
            "nl/rijksoverheid/moz/nmc/migratie/V16MigratieTest.java", "eigen embedded PostgreSQL, toetst een migratie");

    @Test
    void elkeTestklasse_isQuarkusUnitOfUitgezonderd() throws IOException {
        List<String> zonderIndeling = new ArrayList<>();

        for (Path bestand : testklassen()) {
            String bron = Files.readString(bestand);
            String naam = relatief(bestand);
            int indelingen = (QUARKUS.matcher(bron).find() ? 1 : 0) + (UNIT.matcher(bron).find() ? 1 : 0)
                    + (UITGEZONDERD.containsKey(naam) ? 1 : 0);

            if (indelingen != 1) {
                zonderIndeling.add(naam + " (" + indelingen + " indelingen)");
            }
        }

        assertEquals(List.of(), zonderIndeling,
                "Geef elke testklasse precies één van: @QuarkusTest, @Tag(\"unit\") of een uitzondering hier");
    }

    // Een uitzondering die niet meer bestaat, verbergt anders een nieuwe klasse met dezelfde naam.
    @Test
    void uitzonderingen_bestaanNogEnHebbenTests() throws IOException {
        for (String naam : UITGEZONDERD.keySet()) {
            Path bestand = TESTBRONNEN.resolve(naam);
            assertTrue(Files.exists(bestand), naam + " bestaat niet meer; haal de uitzondering weg");
            assertTrue(TESTMETHODE.matcher(Files.readString(bestand)).find(), naam + " heeft geen tests");
        }
    }

    private static List<Path> testklassen() throws IOException {
        List<Path> resultaat = new ArrayList<>();

        try (Stream<Path> bestanden = Files.walk(TESTBRONNEN)) {
            for (Path bestand : bestanden.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (TESTMETHODE.matcher(Files.readString(bestand)).find()) {
                    resultaat.add(bestand);
                }
            }
        }

        assertTrue(resultaat.size() > 1, "geen testklassen gevonden onder " + TESTBRONNEN);

        return resultaat;
    }

    private static String relatief(Path bestand) {
        return TESTBRONNEN.relativize(bestand).toString().replace('\\', '/');
    }
}
