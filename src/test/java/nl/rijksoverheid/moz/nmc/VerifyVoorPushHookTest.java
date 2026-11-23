package nl.rijksoverheid.moz.nmc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Test van de Claude Code-hook {@code .claude/hooks/verify-voor-push.sh}, die vóór een git push
 * {@code ./mvnw verify} draait. De hook draait als script tegen een tijdelijke git-repo met een
 * nep-{@code mvnw} die alleen bijhoudt hoe vaak hij is aangeroepen; zo toetst de test de herkenning
 * van het commando zonder een echte build.
 */
class VerifyVoorPushHookTest {

    private static final Path HOOK = Path.of(".claude/hooks/verify-voor-push.sh").toAbsolutePath();
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tijdelijk;

    private Path repo;
    private Path teller;
    private Path nepBin;

    // Zonder jq slaat de hook alles over en falen de tests met een onduidelijke melding.
    @BeforeAll
    static void jqIsGeinstalleerd() {
        assertTrue(zoek("jq").isPresent(),
                "jq is verplicht voor de build; installeer het met brew, dnf of apt install jq");
    }

    @BeforeEach
    void maakRepo() throws Exception {
        repo = Files.createDirectory(tijdelijk.resolve("repo"));
        teller = tijdelijk.resolve("verify-aanroepen");
        nepBin = Files.createDirectory(tijdelijk.resolve("bin"));

        uitvoerbaar(repo.resolve("mvnw"), """
                #!/bin/sh
                echo verify >> "$VERIFY_TELLER"
                exit "${VERIFY_EXIT:-0}"
                """);
        uitvoerbaar(nepBin.resolve("java"), "#!/bin/sh\nexit 0\n");

        git("init", "-q");
        git("config", "user.email", "test@example.org");
        git("config", "user.name", "Test");
        git("config", "commit.gpgsign", "false");
        git("add", "mvnw");
        git("commit", "-q", "-m", "start");
    }

    @Test
    void kalePush_draaitVerifyEnLaatDoor() throws Exception {
        Uitkomst uitkomst = draai("git push");

        assertEquals(0, uitkomst.exitcode(), uitkomst.stderr());
        assertEquals(1, verifyAanroepen());
    }

    @Test
    void kalePush_verifyFaalt_blokkeert() throws Exception {
        Uitkomst uitkomst = draai("git push", Map.of("VERIFY_EXIT", "1"));

        assertEquals(2, uitkomst.exitcode());
        assertTrue(uitkomst.stderr().contains("faalt"), uitkomst.stderr());
    }

    @Test
    void tweedePushOpZelfdeCommit_slaatVerifyOver() throws Exception {
        draai("git push");
        Uitkomst uitkomst = draai("git push origin HEAD");

        assertEquals(0, uitkomst.exitcode(), uitkomst.stderr());
        assertEquals(1, verifyAanroepen());
    }

    @Test
    void dryRun_draaitGeenVerify() throws Exception {
        Uitkomst uitkomst = draai("git push --dry-run");

        assertEquals(0, uitkomst.exitcode(), uitkomst.stderr());
        assertEquals(0, verifyAanroepen());
    }

    @Test
    void commitMetHeredocDieGitPushNoemt_wordtNietAlsPushGezien() throws Exception {
        Files.writeString(repo.resolve("mvnw"), "# gewijzigd\n", StandardOpenOption.APPEND);
        git("add", "mvnw");

        Uitkomst uitkomst = draai("""
                git commit -m "$(cat <<'EOF'
                docs: draai verify vóór een git push

                Ook als het "anders" lijkt, en met de repo's van anderen.
                EOF
                )"
                """);

        assertEquals(0, uitkomst.exitcode(), uitkomst.stderr());
        assertEquals(0, verifyAanroepen());
    }

    @Test
    void prBodyOverMeerdereRegelsDieGitPushNoemt_wordtNietAlsPushGezien() throws Exception {
        Uitkomst uitkomst = draai("""
                gh pr create --title "x" --body "Eerste regel.
                De hook draait vóór git push; zie ook git push --force.
                "
                """);

        assertEquals(0, uitkomst.exitcode(), uitkomst.stderr());
        assertEquals(0, verifyAanroepen());
    }

    @Test
    void pushNaEenHeredoc_wordtWelGezien() throws Exception {
        Uitkomst uitkomst = draai("""
                cat <<EOF > notitie.txt
                tekst
                EOF
                git push
                """);

        assertEquals(0, uitkomst.exitcode(), uitkomst.stderr());
        assertEquals(1, verifyAanroepen());
    }

    @Test
    void pushOverTweeRegelsMetBackslash_wordtGezien() throws Exception {
        Uitkomst uitkomst = draai("git \\\n  push origin HEAD");

        assertEquals(0, uitkomst.exitcode(), uitkomst.stderr());
        assertEquals(1, verifyAanroepen());
    }

    @Test
    void rebaseEnPushBijSchoneWerkboom_blokkeert() throws Exception {
        Uitkomst uitkomst = draai("git rebase origin/main && git push --force-with-lease");

        assertEquals(2, uitkomst.exitcode());
        assertTrue(uitkomst.stderr().contains("én pusht"), uitkomst.stderr());
        assertEquals(0, verifyAanroepen());
    }

    @Test
    void commitVanAlleenNieuweBestandenEnPush_blokkeert() throws Exception {
        Files.writeString(repo.resolve("nieuw.txt"), "nieuw\n");

        Uitkomst uitkomst = draai("git add -A && git commit -m x && git push");

        assertEquals(2, uitkomst.exitcode());
        assertEquals(0, verifyAanroepen());
    }

    @Test
    void pullEnPushOpAparteRegels_blokkeert() throws Exception {
        Uitkomst uitkomst = draai("git pull\ngit push");

        assertEquals(2, uitkomst.exitcode());
        assertEquals(0, verifyAanroepen());
    }

    @Test
    void zonderJq_geenPush_zegtNiets() throws Exception {
        Uitkomst uitkomst = draaiZonderJq("ls -la");

        assertEquals(0, uitkomst.exitcode());
        assertEquals("", uitkomst.stdout());
    }

    @Test
    void zonderJq_push_meldtDatVerifyNietDraait() throws Exception {
        Uitkomst uitkomst = draaiZonderJq("git push");

        assertEquals(0, uitkomst.exitcode());
        assertTrue(uitkomst.stdout().contains("jq ontbreekt"), uitkomst.stdout());
        assertEquals(0, verifyAanroepen());
    }

    private Uitkomst draai(String commando) throws Exception {
        return draai(commando, Map.of());
    }

    private Uitkomst draai(String commando, Map<String, String> extra) throws Exception {
        String pad = nepBin + File.pathSeparator + System.getenv("PATH");

        return start(commando, pad, extra);
    }

    // Een PATH met alleen de programma's die de hook zonder jq nodig heeft.
    private Uitkomst draaiZonderJq(String commando) throws Exception {
        Path beperkt = Files.createDirectory(tijdelijk.resolve("zonder-jq"));

        for (String programma : List.of("cat", "grep", "printf")) {
            zoek(programma).ifPresent(bron -> maakLink(beperkt.resolve(programma), bron));
        }

        return start(commando, beperkt.toString(), Map.of());
    }

    private Uitkomst start(String commando, String pad, Map<String, String> extra) throws Exception {
        String invoer = JSON.writeValueAsString(Map.of(
                "tool_input", Map.of("command", commando),
                "cwd", repo.toString()));

        ProcessBuilder builder = new ProcessBuilder(zoek("bash").orElseThrow().toString(), HOOK.toString())
                .directory(repo.toFile());
        Map<String, String> omgeving = builder.environment();
        omgeving.put("PATH", pad);
        // Een lege HOME, zodat de hook geen echte SDKMAN-Java laadt.
        omgeving.put("HOME", tijdelijk.toString());
        omgeving.put("VERIFY_TELLER", teller.toString());
        omgeving.putAll(extra);

        Process proces = builder.start();
        proces.getOutputStream().write(invoer.getBytes(StandardCharsets.UTF_8));
        proces.getOutputStream().close();

        if (!proces.waitFor(60, TimeUnit.SECONDS)) {
            proces.destroyForcibly();
            fail("De hook liep niet binnen 60 seconden af");
        }

        return new Uitkomst(proces.exitValue(),
                new String(proces.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(proces.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private int verifyAanroepen() throws IOException {
        return Files.exists(teller) ? Files.readAllLines(teller).size() : 0;
    }

    private void git(String... argumenten) throws Exception {
        List<String> opdracht = new ArrayList<>(List.of("git", "-C", repo.toString()));
        opdracht.addAll(Arrays.asList(argumenten));
        Process proces = new ProcessBuilder(opdracht).redirectErrorStream(true).start();
        String uitvoer = new String(proces.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, proces.waitFor(), "git " + String.join(" ", argumenten) + ": " + uitvoer);
    }

    private static Optional<Path> zoek(String programma) {
        return Arrays.stream(System.getenv("PATH").split(File.pathSeparator))
                .map(map -> Path.of(map, programma))
                .filter(Files::isExecutable)
                .findFirst();
    }

    private static void maakLink(Path link, Path doel) {
        try {
            Files.createSymbolicLink(link, doel);
        } catch (IOException e) {
            throw new IllegalStateException("Kan " + link + " niet aanmaken", e);
        }
    }

    private static void uitvoerbaar(Path bestand, String inhoud) throws IOException {
        Files.writeString(bestand, inhoud);
        Files.setPosixFilePermissions(bestand, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private record Uitkomst(int exitcode, String stdout, String stderr) {
    }
}
