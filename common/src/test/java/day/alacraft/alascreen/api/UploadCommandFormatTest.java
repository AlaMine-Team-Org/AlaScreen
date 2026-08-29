package day.alacraft.alascreen.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape of the command the share button runs.
 *
 * <p>A copy of the formatter rather than a call into it: the real one lives in
 * {@code common-mc}, which compiles against Minecraft and has no test lane, and
 * the rule it encodes is worth pinning somewhere that runs. If the two ever
 * disagree, this test is the specification.
 *
 * <p>Why it exists at all: the button's argument went missing in a live session
 * and the command arrived as a bare {@code /alascreen upload}. Brigadier replied
 * "unknown or incomplete command", which tells a player nothing, and the shape
 * of the command was the one thing nobody had checked.
 */
class UploadCommandFormatTest {

    /** Mirrors {@code ScreenshotHook.uploadCommand}. */
    private static String uploadCommand(String fileName) {
        String escaped = fileName.replace("\\", "\\\\").replace("\"", "\\\"");

        return "alascreen upload \"" + escaped + "\"";
    }

    @Test
    void wrapsAnOrdinaryNameInQuotes() {
        assertEquals("alascreen upload \"2026-08-29_18.24.32.png\"",
                uploadCommand("2026-08-29_18.24.32.png"));
    }

    @Test
    void aNameWithSpacesStaysOneArgument() {
        String command = uploadCommand("my best shot.png");

        // Unquoted, Brigadier would read "my" and choke on the rest — which is
        // exactly how an argument goes missing without anything logging why.
        assertEquals("alascreen upload \"my best shot.png\"", command);
        assertEquals(2, command.chars().filter(c -> c == '"').count());
    }

    @Test
    void quotesInsideTheNameAreEscapedRatherThanEndingTheArgument() {
        String command = uploadCommand("odd\"name.png");

        assertTrue(command.contains("odd\\\"name.png"),
                "an unescaped quote would close the argument early");
        assertTrue(command.startsWith("alascreen upload \""));
        assertTrue(command.endsWith("\""));
    }

    @Test
    void backslashesAreEscapedBeforeQuotes() {
        // Order matters: escaping quotes first would then escape the backslash
        // that was just added, doubling it.
        assertEquals("alascreen upload \"back\\\\slash.png\"", uploadCommand("back\\slash.png"));
    }

    @Test
    void theCommandAlwaysCarriesAnArgument() {
        // The failure being guarded: "alascreen upload" with nothing after it.
        for (String name : new String[]{"a.png", "2026-08-29_18.24.32.png", "shot 1.png"}) {
            String command = uploadCommand(name);

            assertTrue(command.length() > "alascreen upload ".length(),
                    "the command must not degrade to a bare literal for: " + name);
        }
    }
}
