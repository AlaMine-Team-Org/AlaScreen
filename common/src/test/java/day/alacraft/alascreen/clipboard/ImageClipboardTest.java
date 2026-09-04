package day.alacraft.alascreen.clipboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of the clipboard that can be judged without a clipboard.
 *
 * <p>Deliberately not a test of the copy itself: that ends in the operating
 * system's own pasteboard, and a test that reaches it would either fail on a
 * headless build or leave whatever ran last sitting in a developer's clipboard.
 * What is tested here is everything that decides <em>how</em> the copy is
 * attempted — the platform split and the AppleScript command — plus the refusals
 * that must never turn into an exception.
 */
class ImageClipboardTest {

    @Test
    void macIsTheOnlyPlatformTreatedDifferently() {
        // The names a JVM actually reports, not the ones a person would guess.
        assertEquals(ImageClipboard.Platform.MAC, ImageClipboard.Platform.of("Mac OS X"));
        assertEquals(ImageClipboard.Platform.MAC, ImageClipboard.Platform.of("macOS"));
        assertEquals(ImageClipboard.Platform.MAC, ImageClipboard.Platform.of("Darwin"));

        assertEquals(ImageClipboard.Platform.OTHER, ImageClipboard.Platform.of("Windows 11"));
        assertEquals(ImageClipboard.Platform.OTHER, ImageClipboard.Platform.of("Linux"));
        assertEquals(ImageClipboard.Platform.OTHER, ImageClipboard.Platform.of("FreeBSD"));
    }

    @Test
    void anUnknownPlatformIsNotMistakenForMac() {
        // The fallback matters: guessing MAC would spawn osascript on a machine
        // that has none, turning a working AWT copy into a silent failure.
        assertEquals(ImageClipboard.Platform.OTHER, ImageClipboard.Platform.of(""));
        assertEquals(ImageClipboard.Platform.OTHER, ImageClipboard.Platform.of(null));
    }

    @Test
    void theMacCommandAsksOsascriptForPngData(@TempDir Path folder) {
        List<String> command = MacPasteboard.command(folder.resolve("shot.png"));

        assertEquals("osascript", command.get(0));
        assertEquals("-e", command.get(1));

        String script = command.get(2);

        // The four-character code is the whole point: without it the clipboard
        // receives a file reference, and pasting into a chat client attaches a
        // file instead of showing a picture.
        assertTrue(script.contains("«class PNGf»"), script);
        assertTrue(script.startsWith("set the clipboard to (read (POSIX file \""), script);
        assertTrue(script.contains("shot.png"), script);
    }

    @Test
    void theCommandIsAnArgumentVectorRatherThanAShellString(@TempDir Path folder) {
        // Three arguments exactly. A single string would be handed to a shell,
        // and the path arrives from a chat command.
        assertEquals(3, MacPasteboard.command(folder.resolve("shot.png")).size());
    }

    @Test
    void quotesAndBackslashesInAPathCannotEndTheAppleScriptString() {
        // Windows cannot create such a file, and macOS can. The escaping has to
        // be right for the platform that runs this code, not the one testing it.
        assertEquals("/tmp/it\\\"s.png", MacPasteboard.escape("/tmp/it\"s.png"));
        assertEquals("/tmp/back\\\\slash.png", MacPasteboard.escape("/tmp/back\\slash.png"));
        assertEquals("/tmp/plain.png", MacPasteboard.escape("/tmp/plain.png"));
    }

    @Test
    void aLineBreakInANameCannotSplitTheScript() {
        // macOS forbids only '/' and NUL in a file name, so a newline is a legal
        // one — and an AppleScript literal cannot span lines. Unescaped, the
        // script does not fail to copy, it fails to compile, and the player is
        // told the clipboard refused with nothing to act on.
        assertEquals("/tmp/two\\nlines.png", MacPasteboard.escape("/tmp/two\nlines.png"));
        assertEquals("/tmp/carriage\\rreturn.png", MacPasteboard.escape("/tmp/carriage\rreturn.png"));
        assertEquals("/tmp/tab\\there.png", MacPasteboard.escape("/tmp/tab\there.png"));
    }

    @Test
    void escapingHappensInAnOrderThatCannotDoubleBackOnItself() {
        // Backslashes must be escaped before anything that introduces one, or
        // the escape characters added later get escaped again on a second pass.
        assertEquals("/tmp/\\\\\\\"both.png", MacPasteboard.escape("/tmp/\\\"both.png"));
    }

    @Test
    void aMissingFileIsAnOutcomeAndNotAnException(@TempDir Path folder) {
        assertEquals(CopyOutcome.FILE_MISSING, ImageClipboard.copy(folder.resolve("gone.png")));
        assertEquals(CopyOutcome.FILE_MISSING, ImageClipboard.copy(null));
    }

    @Test
    void aFolderIsNotAPicture(@TempDir Path folder) throws Exception {
        Path directory = Files.createDirectory(folder.resolve("screenshots"));

        assertEquals(CopyOutcome.FILE_MISSING, ImageClipboard.copy(directory));
    }

    @Test
    void onlyTheGoodEndingReadsAsSuccess() {
        // ShareController colours the chat line by this: everything that is not
        // COPIED has to arrive in red, including outcomes added later.
        assertTrue(CopyOutcome.COPIED.succeeded());

        for (CopyOutcome outcome : CopyOutcome.values()) {
            if (outcome != CopyOutcome.COPIED) {
                assertFalse(outcome.succeeded(), outcome.name());
            }
        }
    }

    @Test
    void everyOutcomeCarriesItsOwnMessage() {
        for (CopyOutcome outcome : CopyOutcome.values()) {
            assertTrue(outcome.messageKey().startsWith("alascreen."), outcome.name());
        }

        // Distinct keys, or two different endings would read the same in chat.
        long distinct = java.util.Arrays.stream(CopyOutcome.values())
                .map(CopyOutcome::messageKey)
                .distinct()
                .count();

        assertEquals(CopyOutcome.values().length, distinct);
    }
}
