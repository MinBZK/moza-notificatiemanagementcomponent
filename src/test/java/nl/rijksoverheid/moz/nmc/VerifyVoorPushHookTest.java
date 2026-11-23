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
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
    void kalePush_naMislukteVerify_verifieertOpnieuw() throws Exception {
        Uitkomst eerste = draai("git push", Map.of("VERIFY_EXIT", "1"));
        Uitkomst tweede = draai("git push", Map.of("VERIFY_EXIT", "1"));

        assertEquals(2, eerste.exitcode());
        assertEquals(2, tweede.exitcode());
        assertEquals(2, verifyAanroepen());
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
    void heredocBuitenAanhalingstekensDieGitPushNoemt_wordtNietAlsPushGezien() throws Exception {
        Uitkomst uitkomst = draai("""
                gh pr create --title x --body-file - <<'EOF'
                Draai verify voor git push
                EOF
                """);

        assertEquals(0, uitkomst.exitcode(), uitkomst.stderr());
        assertEquals(0, verifyAanroepen());
    }

    @Test
    void checkoutEnPush_blokkeert() throws Exception {
        maakKapotteBranch();

        Uitkomst uitkomst = draai("git checkout kapot && git push -u origin kapot");

        assertEquals(2, uitkomst.exitcode());
        assertTrue(uitkomst.stderr().contains("én pusht"), uitkomst.stderr());
        assertEquals(0, verifyAanroepen());
    }

    @Test
    void resetEnPush_blokkeert() throws Exception {
        maakKapotteBranch();

        Uitkomst uitkomst = draai("git reset --hard kapot && git push -f");

        assertEquals(2, uitkomst.exitcode());
        assertEquals(0, verifyAanroepen());
    }

    @Test
    void pushVanAndereBranch_verifieertDieBranch() throws Exception {
        String kapot = maakKapotteBranch();

        Uitkomst uitkomst = draai("git push origin kapot");

        assertEquals(2, uitkomst.exitcode());
        assertTrue(uitkomst.stderr().contains(kapot), uitkomst.stderr());
    }

    @Test
    void pushMetBronEnDoel_verifieertDeBron() throws Exception {
        maakKapotteBranch();

        Uitkomst uitkomst = draai("git push --force-with-lease origin +kapot:main");

        assertEquals(2, uitkomst.exitcode());
    }

    @Test
    void pushVanHeadNaarAnderDoel_verifieertHead() throws Exception {
        maakKapotteBranch();

        Uitkomst uitkomst = draai("git push -o ci.skip origin HEAD:kapot");

        assertEquals(0, uitkomst.exitcode(), uitkomst.stderr());
        assertEquals(1, verifyAanroepen());
    }

    @Test
    void pushAll_verifieertHeadEnMeldtDat() throws Exception {
        Uitkomst uitkomst = draai("git push --all origin");

        assertEquals(0, uitkomst.exitcode(), uitkomst.stderr());
        assertTrue(uitkomst.stdout().contains("alleen HEAD"), uitkomst.stdout());
        assertEquals(1, verifyAanroepen());
    }

    @Test
    void verwijderenVanRemoteBranch_draaitGeenVerify() throws Exception {
        assertEquals(0, draai("git push origin :oud").exitcode());
        assertEquals(0, draai("git push origin --delete oud").exitcode());
        assertEquals(0, verifyAanroepen());
    }

    @Test
    void cdNaarRepoVanuitAndereMap_verifieertDieRepo() throws Exception {
        Uitkomst uitkomst = draaiVanuit(tijdelijk, "cd " + repo + " && git push");

        assertEquals(0, uitkomst.exitcode(), uitkomst.stderr());
        assertEquals(1, verifyAanroepen());
    }

    @Test
    void gitMinCNaarRepoVanuitAndereMap_verifieertDieRepo() throws Exception {
        Uitkomst uitkomst = draaiVanuit(tijdelijk, "git -C " + repo + " push");

        assertEquals(0, uitkomst.exitcode(), uitkomst.stderr());
        assertEquals(1, verifyAanroepen());
    }

    @Test
    void pushBuitenEenRepo_laatDoorZonderVerify() throws Exception {
        Uitkomst uitkomst = draaiVanuit(tijdelijk, "git push");

        assertEquals(0, uitkomst.exitcode(), uitkomst.stderr());
        assertEquals(0, verifyAanroepen());
    }

    @Test
    void zonderJava_meldtDatVerifyNietDraait() throws Exception {
        Path pad = beperktPad("zonder-java", "cat", "jq", "awk", "grep", "sed", "tail", "git", "sort");

        Uitkomst uitkomst = start("git push", repo, pad.toString(), Map.of());

        assertEquals(0, uitkomst.exitcode(), uitkomst.stderr());
        assertTrue(uitkomst.stdout().contains("geen Java"), uitkomst.stdout());
        assertEquals(0, verifyAanroepen());
    }

    @Test
    void worktreeAanmakenFaalt_meldtDatVerifyNietDraait() throws Exception {
        Path worktrees = Files.createDirectories(repo.resolve(".git/worktrees"));
        Files.setPosixFilePermissions(worktrees, PosixFilePermissions.fromString("r-xr-xr-x"));

        try {
            // Als root negeert het bestandssysteem de rechten; dan valt er niets te testen.
            assumeFalse(Files.isWritable(worktrees), "rechten worden niet afgedwongen");

            Uitkomst uitkomst = draai("git push");

            assertEquals(0, uitkomst.exitcode(), uitkomst.stderr());
            assertTrue(uitkomst.stdout().contains("tijdelijke worktree"), uitkomst.stdout());
            assertEquals(0, verifyAanroepen());
        } finally {
            Files.setPosixFilePermissions(worktrees, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
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
        return start(commando, repo, volledigPad(), extra);
    }

    private Uitkomst draaiVanuit(Path cwd, String commando) throws Exception {
        return start(commando, cwd, volledigPad(), Map.of());
    }

    private String volledigPad() {
        return nepBin + File.pathSeparator + System.getenv("PATH");
    }

    private Uitkomst draaiZonderJq(String commando) throws Exception {
        return start(commando, repo, beperktPad("zonder-jq", "cat", "grep").toString(), Map.of());
    }

    // Een PATH met alleen de genoemde programma's, om een ontbrekend programma na te bootsen.
    private Path beperktPad(String naam, String... programmas) throws IOException {
        Path map = Files.createDirectory(tijdelijk.resolve(naam));

        for (String programma : programmas) {
            maakLink(map.resolve(programma), zoek(programma).orElseThrow(
                    () -> new IllegalStateException(programma + " staat niet op PATH")));
        }

        return map;
    }

    // Een branch waarop verify faalt; geeft de korte hash van zijn commit terug.
    private String maakKapotteBranch() throws Exception {
        git("checkout", "-q", "-b", "kapot");
        uitvoerbaar(repo.resolve("mvnw"), """
                #!/bin/sh
                echo verify >> "$VERIFY_TELLER"
                exit 1
                """);
        git("commit", "-q", "-am", "kapot");
        String hash = git("rev-parse", "--short", "HEAD").strip();
        git("checkout", "-q", "-");

        return hash;
    }

    private Uitkomst start(String commando, Path cwd, String pad, Map<String, String> extra) throws Exception {
        String invoer = JSON.writeValueAsString(Map.of(
                "tool_input", Map.of("command", commando),
                "cwd", cwd.toString()));

        ProcessBuilder builder = new ProcessBuilder(zoek("bash").orElseThrow().toString(), HOOK.toString())
                .directory(cwd.toFile());
        Map<String, String> omgeving = builder.environment();
        omgeving.put("PATH", pad);
        // Een lege HOME, zodat de hook geen echte SDKMAN-Java laadt.
        omgeving.put("HOME", tijdelijk.toString());
        omgeving.put("VERIFY_TELLER", teller.toString());
        zonderGitConfigVanBuiten(omgeving);
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

    private String git(String... argumenten) throws Exception {
        List<String> opdracht = new ArrayList<>(List.of("git", "-C", repo.toString()));
        opdracht.addAll(Arrays.asList(argumenten));
        ProcessBuilder builder = new ProcessBuilder(opdracht).redirectErrorStream(true);
        zonderGitConfigVanBuiten(builder.environment());
        Process proces = builder.start();
        String uitvoer = new String(proces.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, proces.waitFor(), "git " + String.join(" ", argumenten) + ": " + uitvoer);

        return uitvoer;
    }

    // Een globale core.hooksPath of commit.template van de ontwikkelaar mag de testrepo niet raken.
    private static void zonderGitConfigVanBuiten(Map<String, String> omgeving) {
        omgeving.put("GIT_CONFIG_GLOBAL", "/dev/null");
        omgeving.put("GIT_CONFIG_NOSYSTEM", "1");
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
