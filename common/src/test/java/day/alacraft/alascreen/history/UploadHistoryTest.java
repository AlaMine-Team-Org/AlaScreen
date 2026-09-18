package day.alacraft.alascreen.history;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adapted from Ala Logger's, whose history is the same class over a different
 * kind of upload. The difference that matters here is the file name: in this
 * mod it is how {@code [copy image]} finds the picture on disk after a restart,
 * so it is checked as closely as the token.
 */
class UploadHistoryTest {

    private static final String URL = "https://alacraft.day/en/screenshots/aB3xY9kM";
    private static final String SHOT = "2026-09-11_14.02.33.png";

    @TempDir
    Path directory;

    /** Where the loaders keep it: a folder named after the mod, in the config directory. */
    private Path file() {
        return directory.resolve("alascreen").resolve("history.json");
    }

    @Test
    void remembersAnUpload() {
        UploadHistory history = new UploadHistory(file());

        UploadRecord record = history.add("aB3xY9kM", URL, "delete-token", SHOT);

        assertEquals(1, history.size());
        assertEquals(record, history.last().orElseThrow());
        assertEquals(record, history.findById("aB3xY9kM").orElseThrow());
        assertTrue(history.findById("nope").isEmpty());
        assertTrue(record.hasToken());
    }

    @Test
    @DisplayName("every field of an upload survives a restart, the file name included")
    void survivesARestart() {
        UploadRecord shared = new UploadRecord("aB3xY9kM", URL, "delete-token",
                Instant.now().minus(1, ChronoUnit.HOURS), SHOT);

        UploadHistory before = new UploadHistory(file());
        before.add(shared);
        before.add("cD4zW8nP", "https://alacraft.day/ru/screenshots/cD4zW8nP", "another-token",
                "2026-09-11_14.05.10.png");

        // A new object over the same file is exactly what quitting the game and
        // starting it again does.
        UploadHistory after = new UploadHistory(file());

        assertEquals(2, after.size());
        assertEquals("cD4zW8nP", after.last().orElseThrow().id());
        // The whole record, not only the token: after an upload the mod keeps
        // no pixels, so a file name lost here is a [copy image] button that
        // reports the screenshot missing while it sits in the folder.
        assertEquals(shared, after.findById("aB3xY9kM").orElseThrow());
    }

    @Test
    void keepsTheNewestEntriesOnly() {
        UploadHistory history = new UploadHistory(file(), 3);

        for (int i = 1; i <= 10; i++) {
            history.add(new UploadRecord("id" + i, "url" + i, "token" + i,
                    Instant.now().minus(10 - i, ChronoUnit.MINUTES), "shot" + i + ".png"));
        }

        assertEquals(3, history.size());
        assertEquals(List.of("id10", "id9", "id8"),
                history.recent(10).stream().map(UploadRecord::id).toList());
        assertEquals(3, new UploadHistory(file(), 3).size());
    }

    @Test
    @DisplayName("the most recent upload comes first, which is what 'delete last' means")
    void returnsTheMostRecentFirst() {
        UploadHistory history = new UploadHistory(file());
        Instant now = Instant.now();

        history.add(new UploadRecord("older", "u", "t", now.minus(2, ChronoUnit.HOURS), "a.png"));
        history.add(new UploadRecord("newest", "u", "t", now, "b.png"));
        history.add(new UploadRecord("middle", "u", "t", now.minus(1, ChronoUnit.HOURS), "c.png"));

        assertEquals("newest", history.last().orElseThrow().id());
        assertEquals(List.of("newest", "middle"),
                history.recent(2).stream().map(UploadRecord::id).toList());
        assertEquals(List.of(), history.recent(0));
        assertEquals(3, history.recent(99).size());
    }

    @Test
    void forgetsAnUpload() {
        UploadHistory history = new UploadHistory(file());
        history.add("aB3xY9kM", URL, "delete-token", SHOT);

        assertTrue(history.remove("aB3xY9kM"));
        assertFalse(history.remove("aB3xY9kM"));
        assertFalse(history.remove(null));
        assertEquals(0, history.size());
        assertEquals(0, new UploadHistory(file()).size());
    }

    @Test
    @DisplayName("re-recording the same id replaces it instead of leaving a stale token behind")
    void replacesDuplicateIds() {
        UploadHistory history = new UploadHistory(file());

        history.add("aB3xY9kM", URL, "first-token", SHOT);
        history.add("aB3xY9kM", URL, "second-token", SHOT);

        assertEquals(1, history.size());
        assertEquals("second-token", history.findById("aB3xY9kM").orElseThrow().token());
    }

    @Test
    @DisplayName("the write is atomic - no half-written file and no temporary left behind")
    void writesAtomically() throws IOException {
        UploadHistory history = new UploadHistory(file());

        for (int i = 1; i <= 5; i++) {
            history.add("id" + i, "url" + i, "token" + i, "shot" + i + ".png");
        }

        try (Stream<Path> entries = Files.list(file().getParent())) {
            assertEquals(Set.of("history.json"),
                    entries.map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
        }

        // Shrinking the history must replace the file, not overwrite its front and
        // leave the tail of the longer version behind as trailing garbage.
        for (int i = 1; i <= 4; i++) {
            history.remove("id" + i);
        }

        assertEquals(1, new UploadHistory(file()).size());
        assertTrue(Files.readString(file(), StandardCharsets.UTF_8).trim().endsWith("}"));
    }

    @Test
    @DisplayName("a temporary file left by a killed game does not block the next write")
    void overwritesAStaleTemporaryFile() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file().resolveSibling("history.json.tmp"), "{ half written",
                StandardCharsets.UTF_8);

        UploadHistory history = new UploadHistory(file());
        history.add("aB3xY9kM", URL, "delete-token", SHOT);

        assertEquals(1, new UploadHistory(file()).size());
        assertFalse(Files.exists(file().resolveSibling("history.json.tmp")));
    }

    @Test
    @DisplayName("a corrupt file costs the tokens but never the game's start")
    void toleratesACorruptFile() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "this is not json at all", StandardCharsets.UTF_8);

        UploadHistory history = new UploadHistory(file());

        assertEquals(0, history.size());

        history.add("aB3xY9kM", URL, "delete-token", SHOT);
        assertEquals(1, new UploadHistory(file()).size());
    }

    @Test
    @DisplayName("one broken entry does not take the other tokens down with it")
    void skipsBrokenEntries() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), """
                {
                  "schemaVersion": 1,
                  "entries": [
                    {"id": "good1", "url": "u", "token": "t", "uploadedAt": "2026-09-11T10:00:00Z", "fileName": "a.png"},
                    {"url": "u", "token": "t"},
                    "not an object",
                    {"id": "good2", "url": "u", "token": "t", "uploadedAt": "nonsense", "fileName": "b.png"}
                  ]
                }
                """, StandardCharsets.UTF_8);

        UploadHistory history = new UploadHistory(file());

        assertEquals(2, history.size());
        assertEquals("good1", history.last().orElseThrow().id());
        // An unreadable timestamp sorts last but keeps its token.
        assertEquals("t", history.findById("good2").orElseThrow().token());
    }

    @Test
    @DisplayName("the file holds credentials, so nobody but its owner may read it")
    void keepsTheFilePrivate() throws IOException {
        UploadHistory history = new UploadHistory(file());
        history.add("aB3xY9kM", URL, "delete-token", SHOT);

        // Both branches are the same assertion in two vocabularies: the file
        // names its owner and nobody else. The Windows half is not optional:
        // an inherited ACL there names everyone the folder does.
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file())));
            return;
        }

        AclFileAttributeView view = Files.getFileAttributeView(file(), AclFileAttributeView.class);
        assertNotNull(view, "no filesystem view can express who may read this file");
        List<UserPrincipal> allowed = view.getAcl().stream().map(AclEntry::principal).distinct().toList();
        assertEquals(List.of(Files.getOwner(file())), allowed);
    }

    @Test
    void refusesRecordsWithoutAnId() {
        assertThrows(IllegalArgumentException.class,
                () -> new UploadRecord("  ", URL, "delete-token", Instant.now(), SHOT));
        assertThrows(NullPointerException.class,
                () -> new UploadRecord(null, URL, "delete-token", Instant.now(), SHOT));
    }

    @Test
    @DisplayName("an upload without a delete token or a file name is still worth remembering for its link")
    void toleratesMissingOptionalFields() {
        UploadHistory history = new UploadHistory(file());

        UploadRecord record = history.add("aB3xY9kM", URL, null, null);

        assertFalse(record.hasToken());
        assertEquals("", record.fileName());
        UploadRecord reloaded = new UploadHistory(file()).findById("aB3xY9kM").orElseThrow();
        assertEquals("", reloaded.token());
        assertEquals(URL, reloaded.url());
    }

    @Test
    @DisplayName("a history.json that is a symlink is written through, not replaced by a regular file")
    void writesThroughASymlink() throws IOException {
        // Instance managers and synced setups link files into a shared place. A
        // rename over the link swaps it for a regular file: the mod carries on
        // with a copy of its own, and the original the link pointed at quietly
        // stops receiving the tokens.
        Path original = directory.resolve("shared").resolve("history.json");
        Files.createDirectories(original.getParent());
        Files.writeString(original, "{\"schemaVersion\": 1, \"entries\": []}", StandardCharsets.UTF_8);
        Files.createDirectories(file().getParent());

        try {
            Files.createSymbolicLink(file(), original);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("this machine will not create symlinks: " + e);
        }

        new UploadHistory(file()).add("aB3xY9kM", URL, "delete-token", SHOT);

        assertTrue(Files.isSymbolicLink(file()), "the link itself must survive the write");
        assertEquals(Optional.of("delete-token"),
                new UploadHistory(original).findById("aB3xY9kM").map(UploadRecord::token),
                "and the token must land in the file the link points at");
    }

    @Test
    @DisplayName("a save survives another program holding the file for a moment, as a virus scanner does")
    void survivesAnotherProgramHoldingTheFile() throws Exception {
        UploadHistory history = new UploadHistory(file());
        history.add("aB3xY9kM", URL, "first-token", SHOT);

        // Windows refuses to replace a file that another program holds open
        // without FILE_SHARE_DELETE, which is how a real-time virus scanner holds
        // one it has just seen written: under one, Ala Logger lost nineteen
        // writes in twenty, each a delete token. A java.io stream opens files
        // without that flag on Windows, so it produces the same refusal here.
        Thread writer = new Thread(() -> history.add("cD4zW8nP",
                "https://alacraft.day/en/screenshots/cD4zW8nP", "second-token", "2026-09-11_14.05.10.png"),
                "history writer");
        boolean refusedAndWaiting;

        FileInputStream scanner = new FileInputStream(file().toFile());
        try {
            writer.start();
            refusedAndWaiting = awaitRetryOrExit(writer);
        } finally {
            scanner.close();
        }

        writer.join(10_000);
        assertFalse(writer.isAlive(), "the save must finish or give up, not hang");

        Optional<String> token = new UploadHistory(file()).findById("cD4zW8nP").map(UploadRecord::token);

        if (!refusedAndWaiting && token.isPresent()) {
            // POSIX replaces a file that is open elsewhere without complaint, so
            // there is no refusal to retry and nothing for this test to hold.
            Assumptions.abort("this platform let the rename replace a file another program holds open");
        }

        assertEquals(Optional.of("second-token"), token,
                "a delete token was lost because another program held the file for a moment");
    }

    /**
     * Waits until the writer has been refused and is sleeping before its next
     * attempt, or has finished without ever waiting.
     *
     * <p>The handle is released on this signal rather than on a timer. A timer
     * can fire before the first attempt, and then the write goes through on the
     * first try and the test passes whether a retry exists or not. Nothing on
     * the path to the rename sleeps, so a writer seen sleeping has been refused.
     * The poll spins rather than sleeps so that the release lands in the first
     * wait and the rest of the retry budget is margin: a sleep on Windows can
     * last a whole timer tick, most of that first wait.
     *
     * @return whether the writer was seen waiting to try again
     */
    private static boolean awaitRetryOrExit(Thread writer) {
        long deadline = System.nanoTime() + 10_000_000_000L;

        while (System.nanoTime() < deadline) {
            Thread.State state = writer.getState();

            if (state == Thread.State.TIMED_WAITING) {
                return true;
            }
            if (state == Thread.State.TERMINATED) {
                return false;
            }

            Thread.onSpinWait();
        }

        return false;
    }

    @Test
    @DisplayName("a history that cannot be written does not cost the player the upload")
    void aFailedWriteDoesNotFailTheUpload() throws IOException {
        // A regular file where the mod's folder should be: nothing can be written
        // below it, on any platform, without touching permissions.
        Files.writeString(file().getParent(), "not a folder", StandardCharsets.UTF_8);
        UploadHistory history = new UploadHistory(file());

        // The history is written from inside the upload's completion, so an
        // exception here would turn a finished upload into an error, and the
        // link the player asked for would never reach the chat.
        UploadRecord record = assertDoesNotThrow(() -> history.add("aB3xY9kM", URL, "delete-token", SHOT));

        // For the rest of this session the token is still there to delete with.
        assertEquals(record, history.last().orElseThrow());
        assertTrue(assertDoesNotThrow(() -> history.remove("aB3xY9kM")));
    }
}
