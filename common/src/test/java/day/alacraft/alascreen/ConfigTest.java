package day.alacraft.alascreen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(config.confirmBeforeUpload,
                "asking before an upload is the mod's one safety feature; it must default on");
        assertTrue(config.saveLocalCopy);
        assertTrue(config.persistHistory);
        assertFalse(config.hasApiToken());
        assertEquals(0, config.maxUploadSide);
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
        written.maxUploadSide = 2560;
        written.save(file);

        Config read = Config.load(file);

        assertEquals("https://example.test/api/v1", read.apiBaseUrl);
        assertEquals("token-123", read.apiToken);
        assertFalse(read.confirmBeforeUpload);
        assertFalse(read.copyLinkToClipboard);
        assertFalse(read.saveLocalCopy);
        assertFalse(read.persistHistory);
        assertEquals(2560, read.maxUploadSide);
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
    void tokenIsTrimmedBecauseItIsPastedByHand() throws IOException {
        Path file = directory.resolve("alascreen.json");
        Files.writeString(file, "{\"apiToken\":\"  abc123  \"}");

        Config config = Config.load(file);

        assertEquals("abc123", config.apiToken);
        assertTrue(config.hasApiToken());
    }

    @Test
    void maxUploadSideIsClampedButZeroStaysOff() throws IOException {
        Path file = directory.resolve("alascreen.json");

        Files.writeString(file, "{\"maxUploadSide\":16}");
        assertEquals(256, Config.load(file).maxUploadSide, "an absurdly small side is a mistake");

        Files.writeString(file, "{\"maxUploadSide\":999999}");
        assertEquals(15360, Config.load(file).maxUploadSide);

        Files.writeString(file, "{\"maxUploadSide\":0}");
        assertEquals(0, Config.load(file).maxUploadSide, "0 means 'do not resize' and must survive");
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
                "{\"apiToken\":\"keep-me\",\"confirmBeforeUpload\":\"yes please\",\"maxUploadSide\":[]}");

        Config config = Config.load(file);

        assertEquals("keep-me", config.apiToken, "one bad key must not cost the others");
        assertTrue(config.confirmBeforeUpload);
        assertEquals(0, config.maxUploadSide);
    }

    @Test
    void unknownKeysAreIgnored() throws IOException {
        Path file = directory.resolve("alascreen.json");
        Files.writeString(file, "{\"apiToken\":\"t\",\"somethingFromTheFuture\":42}");

        assertEquals("t", Config.load(file).apiToken);
    }
}
