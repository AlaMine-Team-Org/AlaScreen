package day.alacraft.alascreen;

import day.alacraft.alascreen.api.AlaScreenApi;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config file, including the shapes a human hand leaves behind.
 *
 * <p>The rule this class is built around: a broken config never stops the game.
 * Every test here is a way a player could plausibly break it.
 */
class ConfigTest {

    @TempDir
    Path directory;

    @Test
    void defaultsAreSafeAndTheQuestionIsOn() {
        Config config = Config.load(directory.resolve("alascreen.json"));

        assertEquals(AlaScreen.DEFAULT_API_BASE_URL, config.apiBaseUrl);
        assertEquals(AlaScreen.DEFAULT_API_BASE_URL, config.effectiveApiBaseUrl());
        assertTrue(config.confirmBeforeUpload,
                "asking before an upload is the mod's one safety feature; it must default on");
        assertTrue(config.saveLocalCopy);
        assertTrue(config.persistHistory);
        assertTrue(config.toastAfterCapture,
                "the plaque is what makes F2 lead anywhere without opening the chat");
        assertFalse(config.hasApiToken());
    }

    @Test
    void writesTheFileOnFirstLoad() throws IOException {
        Path file = directory.resolve("alascreen.json");

        Config.load(file);

        assertTrue(Files.exists(file), "the player has to have a file to edit");
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("confirmBeforeUpload"));
    }

    @Test
    void roundTripsEveryValue() throws IOException {
        Path file = directory.resolve("alascreen.json");

        Config written = Config.load(file);
        written.apiBaseUrl = "https://example.test/api/v1";
        written.apiToken = "token-123";
        written.confirmBeforeUpload = false;
        written.copyLinkToClipboard = false;
        written.saveLocalCopy = false;
        written.persistHistory = false;
        written.toastAfterCapture = false;
        written.save(file);

        Config read = Config.load(file);

        assertEquals("https://example.test/api/v1", read.apiBaseUrl);
        assertEquals("token-123", read.apiToken);
        assertFalse(read.confirmBeforeUpload);
        assertFalse(read.copyLinkToClipboard);
        assertFalse(read.saveLocalCopy);
        assertFalse(read.persistHistory);
        assertFalse(read.toastAfterCapture);
    }

    @Test
    void trailingSlashesAreTrimmedSoUrlsDoNotDoubleUp() throws IOException {
        Path file = directory.resolve("alascreen.json");
        Files.writeString(file, "{\"apiBaseUrl\":\"https://example.test/api/v1///\"}");

        Config config = Config.load(file);

        // Requests are built by concatenation, so a trailing slash would produce
        // //screenshots and a 404 that looks like the site is down.
        assertEquals("https://example.test/api/v1", config.apiBaseUrl);
    }

    @Test
    void blankBaseUrlFallsBackRatherThanFailingLater() throws IOException {
        Path file = directory.resolve("alascreen.json");
        Files.writeString(file, "{\"apiBaseUrl\":\"   \"}");

        assertEquals(AlaScreen.DEFAULT_API_BASE_URL, Config.load(file).apiBaseUrl);
    }

    @Test
    void anUnusableAddressIsStoodInForButNeverWrittenOver() throws IOException {
        Path file = directory.resolve("alascreen.json");
        // A self-hoster who forgot the scheme - the commonest way to get this wrong.
        Files.writeString(file, "{\"apiBaseUrl\":\"screens.example.test/api/v1\",\"apiToken\":\"t\"}");

        Config config = Config.load(file);

        assertEquals("screens.example.test/api/v1", config.apiBaseUrl,
                "the field keeps what the player wrote");
        assertEquals(AlaScreen.DEFAULT_API_BASE_URL, config.effectiveApiBaseUrl(),
                "while the default stands in for it, so the mod still works");

        // "Do not ask again" saves the config in the middle of a session. That
        // save is what used to write our address over the player's: the fallback
        // lived in the field, so the file lost the typo that needed fixing, read
        // as valid from then on, and never produced the error again.
        config.confirmBeforeUpload = false;
        config.save(file);

        Config reread = Config.load(file);

        assertEquals("screens.example.test/api/v1", reread.apiBaseUrl,
                "a save after the fallback must still carry the player's own address");
        assertFalse(reread.confirmBeforeUpload, "and the setting the player actually changed");
    }

    @Test
    void onlyTheConfigEverAssignsTheAddress() throws IOException {
        // The fallback was written into the field from the two entrypoints - in
        // other modules, where no test of this class could see it happen. So the
        // sources are read: outside Config itself, nothing assigns apiBaseUrl.
        Pattern assignment = Pattern.compile("\\.\\s*apiBaseUrl\\s*=(?!=)");

        assertTrue(assignment.matcher("config.apiBaseUrl = AlaScreen.DEFAULT_API_BASE_URL;").find()
                        && !assignment.matcher("config.apiBaseUrl == other").find(),
                "the pattern has to know an assignment from a comparison before its silence means anything");

        List<String> scanned = new ArrayList<>();
        List<String> offenders = new ArrayList<>();

        for (Path root : List.of(Path.of("src/main/java"), Path.of("../common-mc/src/main/java"),
                Path.of("../common-mc/src/mc"), Path.of("../fabric/src/main/java"), Path.of("../neoforge/src/main/java"))) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String name = file.getFileName().toString();
                    scanned.add(name);

                    if (!name.equals("Config.java")
                            && assignment.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                        offenders.add(root.relativize(file).toString());
                    }
                }
            }
        }

        assertTrue(scanned.contains("AlaScreenFabric.java") && scanned.contains("AlaScreenNeoForge.java"),
                "both entrypoints have to be among the files read, or this proves nothing: " + scanned);
        assertEquals(List.of(), offenders,
                "apiBaseUrl is the player's value; a fallback belongs in effectiveApiBaseUrl(), never in the field");
    }

    @Test
    void theClientIsNeverHandedAnAddressItRefuses() throws IOException {
        // Each of these reaches the client's builder through the entrypoint,
        // where an exception would stop the loader from loading the mod at all.
        List<String> written = List.of("", "   ", "screens.example.test/api/v1", "ftp://example.test/api",
                "https://exa mple.test/api", "htps://example.test/api", "https://example.test/api/v1/");

        for (int i = 0; i < written.size(); i++) {
            String value = written.get(i);
            Path file = directory.resolve("alascreen-" + i + ".json");
            Files.writeString(file, "{\"apiBaseUrl\":\"" + value + "\"}");

            String effective = Config.load(file).effectiveApiBaseUrl();

            assertDoesNotThrow(() -> AlaScreenApi.builder(effective).build().close(),
                    "the config handed the client an address it throws on, from \"" + value + "\"");
        }

        Path valid = directory.resolve("valid.json");
        Files.writeString(valid, "{\"apiBaseUrl\":\"https://example.test/api/v1/\"}");
        assertEquals("https://example.test/api/v1", Config.load(valid).effectiveApiBaseUrl(),
                "a usable address is used as written, not replaced");
    }

    @Test
    void saveReplacesTheFileRatherThanRewritingItInPlace() throws IOException {
        Path file = directory.resolve("alascreen.json");
        Config config = Config.load(file);
        config.apiToken = "first";
        config.save(file);

        // A second name for the same bytes on disk. A write in place truncates
        // and refills those very bytes, so this name would see the new content
        // too - and a crash between the truncate and the fill would leave an
        // empty config and a lost token. A replacement composes a whole new file
        // and swaps it in, so the old bytes, still reachable here, stay intact.
        Path secondName = directory.resolve("second-name.json");
        try {
            Files.createLink(secondName, file);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("this filesystem will not create hard links: " + e);
        }

        config.apiToken = "second";
        config.save(file);

        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"second\""));
        assertTrue(Files.readString(secondName, StandardCharsets.UTF_8).contains("\"first\""),
                "the config was rewritten in place instead of being replaced");
    }

    @Test
    void aSavedConfigIsReadableByItsOwnerOnly() throws IOException {
        Path file = directory.resolve("alascreen.json");
        Config config = Config.load(file);
        config.apiToken = "secret";
        config.save(file);

        assertFalse(Files.exists(directory.resolve("alascreen.json.tmp")), "no scratch file is left beside it");

        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
            return;
        }

        AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
        assertNotNull(view, "no filesystem view can express who may read this file");
        List<UserPrincipal> allowed = view.getAcl().stream().map(AclEntry::principal).distinct().toList();
        assertEquals(List.of(Files.getOwner(file)), allowed);
    }

    @Test
    void tokenIsTrimmedBecauseItIsPastedByHand() throws IOException {
        Path file = directory.resolve("alascreen.json");
        Files.writeString(file, "{\"apiToken\":\"  abc123  \"}");

        Config config = Config.load(file);

        assertEquals("abc123", config.apiToken);
        assertTrue(config.hasApiToken());
    }

    @Test
    void aRetiredKeyIsDroppedAndTheRestSurvives() throws IOException {
        Path file = directory.resolve("alascreen.json");
        // maxUploadSide was documented and never applied; files written by 0.1.x
        // still carry it. Loading one must lose nothing else, and the key must
        // not stay in the file looking like a setting that does something.
        Files.writeString(file, "{\"apiToken\":\"keep-me\",\"maxUploadSide\":2560,\"saveLocalCopy\":false}");

        Config config = Config.load(file);

        assertEquals("keep-me", config.apiToken);
        assertFalse(config.saveLocalCopy);
        assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains("maxUploadSide"));
    }

    @Test
    void brokenJsonLeavesTheFileAloneAndUsesDefaults() throws IOException {
        Path file = directory.resolve("alascreen.json");
        String broken = "{ this is not json";
        Files.writeString(file, broken);

        Config config = Config.load(file);

        assertEquals(AlaScreen.DEFAULT_API_BASE_URL, config.apiBaseUrl);
        assertEquals(broken, Files.readString(file, StandardCharsets.UTF_8),
                "overwriting a file the player mistyped would destroy their token");
    }

    @Test
    void wrongTypesFallBackPerKeyInsteadOfDiscardingTheFile() throws IOException {
        Path file = directory.resolve("alascreen.json");
        Files.writeString(file,
                "{\"apiToken\":\"keep-me\",\"confirmBeforeUpload\":\"yes please\",\"persistHistory\":[]}");

        Config config = Config.load(file);

        assertEquals("keep-me", config.apiToken, "one bad key must not cost the others");
        assertTrue(config.confirmBeforeUpload);
        assertTrue(config.persistHistory);
    }

    @Test
    void unknownKeysAreIgnored() throws IOException {
        Path file = directory.resolve("alascreen.json");
        Files.writeString(file, "{\"apiToken\":\"t\",\"somethingFromTheFuture\":42}");

        assertEquals("t", Config.load(file).apiToken);
    }
}
