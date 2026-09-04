package day.alacraft.alascreen.clipboard;

import day.alacraft.alascreen.AlaScreen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The clipboard on macOS, where AWT is not an option.
 *
 * <p>Minecraft turns AWT off precisely because of this platform: GLFW requires
 * the process's first thread and AppKit wants the same one. The two published
 * mods that copy screenshots solve it with a native bridge — an Objective-C
 * binding in one, a Rust library in the other. Both are a heavier dependency
 * than this mod's shared module has ever carried, and both have to ship
 * per-architecture binaries through the publishing pipeline.
 *
 * <p>{@code osascript} is on every macOS install, reads the file itself — so no
 * image data crosses the process boundary — and costs nothing when unused.
 */
final class MacPasteboard {

    /**
     * The AppleScript four-character code for PNG data on the pasteboard.
     *
     * <p>The guillemets are part of the syntax, not decoration. The file is
     * compiled as UTF-8, so the argument reaches {@code osascript} unchanged.
     */
    private static final String PNG_CLASS = "«class PNGf»";

    /** Long enough for a cold start of osascript, short enough not to hang a session. */
    private static final int TIMEOUT_SECONDS = 15;

    private MacPasteboard() {
    }

    static CopyOutcome copy(Path png) throws Exception {
        Process process = new ProcessBuilder(command(png))
                .redirectErrorStream(true)
                .start();

        // Wait first, read second. The other order looks tidier and is a trap:
        // reading to EOF blocks until the child closes its output, so a wedged
        // osascript would hang here for ever and the timeout below would never
        // get its turn. After the wait, whatever the child managed to write is
        // still sitting in the pipe and can be read at leisure.
        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            AlaScreen.LOGGER.warn("osascript did not finish in {} s: {}", TIMEOUT_SECONDS, output(process));

            return CopyOutcome.FAILED;
        }

        if (process.exitValue() != 0) {
            // macOS is the one platform that cannot be debugged from this
            // project's own machines, so its errors are the ones worth keeping.
            // osascript says things like "execution error: ... (-43)"; without
            // this the log held an exit code and nothing else.
            AlaScreen.LOGGER.warn("osascript refused the copy (exit {}): {}",
                    process.exitValue(), output(process));

            return CopyOutcome.FAILED;
        }

        return CopyOutcome.COPIED;
    }

    /** Whatever the process left in the pipe. Never throws: this runs on the failure path. */
    private static String output(Process process) {
        try (var stream = process.getInputStream()) {
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();

            return text.isEmpty() ? "(no output)" : text;
        } catch (Exception unreadable) {
            return "(output unreadable: " + unreadable + ")";
        }
    }

    /**
     * The exact argument vector handed to {@link ProcessBuilder}.
     *
     * <p>Separate and package-private so it can be tested without a Mac. Passed
     * as a list, never as one shell string: the path comes from a chat command,
     * and a shell would read the punctuation in it.
     */
    static List<String> command(Path png) {
        String script = "set the clipboard to (read (POSIX file \""
                + escape(png.toAbsolutePath().toString())
                + "\") as " + PNG_CLASS + ")";

        return List.of("osascript", "-e", script);
    }

    /**
     * Make a path safe to sit inside an AppleScript string literal.
     *
     * <p>Four characters need it. The quote and the backslash are the obvious
     * pair; the line breaks are not, and they are the ones that bite: macOS
     * forbids only {@code /} and NUL in a file name, so a newline is legal
     * there, while an AppleScript literal cannot span lines. Unescaped, such a
     * name does not fail the copy — it fails to <em>compile the script</em>, and
     * the player is told the clipboard refused for no visible reason.
     *
     * <p>Order matters: backslashes first, or the backslashes this method adds
     * would be escaped again by the passes that follow.
     *
     * <p>Package-private so it can be tested with paths this platform would not
     * let anyone create: Windows accepts none of these characters in a name, and
     * the escaping still has to be right for the machines that do.
     */
    static String escape(String path) {
        return path.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
