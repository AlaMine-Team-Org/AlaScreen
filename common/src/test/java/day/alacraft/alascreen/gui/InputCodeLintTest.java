package day.alacraft.alascreen.gui;

import day.alacraft.alascreen.gui.ArgbColorLintTest.SourceFile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Key and mouse codes are named, never numbered, and never come from GLFW.
 *
 * <p>The mod builds for Minecraft 26.2 and 26.3 from one source. 26.3 replaced
 * GLFW with SDL3 and renumbered every input code: F7 296 -> 64, Z 90 -> 29, the
 * left mouse button 0 -> 1, the right one 1 -> 3 (read from both jars with
 * javap). The constants in {@code InputConstants} are {@code static final int},
 * so the compiler copies their values into our bytecode - a numeric literal,
 * or a GLFW constant, keeps compiling and silently matches the wrong key.
 *
 * <p>This is not hypothetical. The markup panel tested {@code button != 0}: on
 * 26.3 a left click is 1, so drawing on the preview would simply never start,
 * with nothing thrown and nothing logged. Ctrl+Z compared with GLFW's Z, which
 * on 26.3 is the code of Numpad 2.
 *
 * <p>What fails: anything from {@code org.lwjgl.glfw}; {@code InputConstants.Type
 * .KEYSYM} or {@code SCANCODE} (gone in 26.3); an input accessor or a
 * button/key variable compared with a number; a {@code KeyMapping} built with a
 * numeric key. Comments and strings are ignored, the same skeleton the colour
 * lint uses.
 */
class InputCodeLintTest {

    private static final String ACCESSORS = "button|key|keycode|keyCode|scancode|scanCode|input|shortcutKey";

    private static final String NUMBER = "-?(?:0[xX][0-9A-Fa-f_]+|[0-9][0-9_]*)";

    private static final List<Rule> RULES = List.of(
            new Rule("GLFW is gone in 26.3 - use InputConstants",
                    Pattern.compile("\\borg\\.lwjgl\\.glfw\\b|\\bGLFW\\b")),
            new Rule("InputConstants.Type.KEYSYM/SCANCODE do not exist in 26.3 - use new KeyMapping(name, InputConstants.KEY_X, category)",
                    Pattern.compile("\\bType\\s*\\.\\s*(?:KEYSYM|SCANCODE)\\b")),
            new Rule("an input code compared with a number - use the named InputConstants constant",
                    Pattern.compile("(?:\\.\\s*(?:" + ACCESSORS + ")\\s*\\(\\s*\\)|(?<![\\w$.])(?:" + ACCESSORS
                            + "))\\s*[=!]=\\s*" + NUMBER + "(?![\\w.])")),
            new Rule("a number compared with an input code - use the named InputConstants constant",
                    Pattern.compile("(?<![\\w$.])" + NUMBER + "\\s*[=!]=\\s*(?:[\\w$]+\\s*\\.\\s*)?(?:" + ACCESSORS
                            + ")\\b")),
            new Rule("a KeyMapping with a numeric key - use the named InputConstants constant",
                    Pattern.compile("\\bnew\\s+KeyMapping\\s*\\([^,()]*,\\s*" + NUMBER + "\\s*[,)]")));

    @Test
    void theModNamesEveryInputCode() throws IOException {
        // Proof first, so a scanner that has stopped finding cannot pass as a
        // clean mod.
        assertScannerWorks();

        List<SourceFile> sources = ArgbColorLintTest.modSources();
        assertTrue(sources.size() > 10, "the mod's sources were not found - the scan would prove nothing");

        List<String> findings = scan(sources);
        assertTrue(findings.isEmpty(),
                "input codes differ between Minecraft 26.2 and 26.3 - name them:\n  "
                        + String.join("\n  ", findings));
    }

    @Test
    void theScannerFindsEveryShapeOfTheMistake() {
        assertScannerWorks();
    }

    @Test
    void theScannerDoesNotCryWolf() {
        String source = """
                import com.mojang.blaze3d.platform.InputConstants;
                class Sample {
                    // if (button != 0) { org.lwjgl.glfw.GLFW.GLFW_KEY_Z }
                    /* event.key() == 90 */
                    private final String decoy = "button == 0, GLFW";

                    boolean ok(KeyEvent event, int button, int count) {
                        KeyMapping mapping = new KeyMapping("key.sample", InputConstants.KEY_F7, CATEGORY);
                        return button != InputConstants.MOUSE_BUTTON_LEFT
                                && event.key() == InputConstants.KEY_Z
                                && count == 0
                                && list.size() == 1
                                && keys.length == 2;
                    }
                }
                """;

        assertEquals(List.of(), scan(List.of(new SourceFile("Sample.java", "Sample", source))),
                "named constants, comments, strings and unrelated numbers must all pass");
    }

    /** One sample per shape the mistake has taken, or could take. */
    private static void assertScannerWorks() {
        String source = """
                import org.lwjgl.glfw.GLFW;
                class Sample {
                    boolean bad(KeyEvent event, MouseButtonEvent click, int button) {
                        KeyMapping a = new KeyMapping("key.a", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, CATEGORY);
                        KeyMapping b = new KeyMapping("key.b", 296, CATEGORY);
                        return button != 0
                                || event.key() == 90
                                || 1 == click.button()
                                || event.scancode() == 0x1D;
                    }
                }
                """;

        List<String> findings = scan(List.of(new SourceFile("Sample.java", "Sample", source)));
        String all = String.join("\n", findings);

        assertTrue(all.contains("Sample.java:1 ") && all.contains("GLFW"), "the GLFW import:\n" + all);
        assertTrue(all.contains("Sample.java:4 ") && all.contains("KEYSYM"), "KEYSYM and a GLFW key:\n" + all);
        assertTrue(all.contains("Sample.java:5 ") && all.contains("numeric key"), "a numbered KeyMapping:\n" + all);
        assertTrue(all.contains("Sample.java:6 "), "button != 0 - the markup panel's real defect:\n" + all);
        assertTrue(all.contains("Sample.java:7 "), "event.key() == 90 - Ctrl+Z's real defect:\n" + all);
        assertTrue(all.contains("Sample.java:8 ") && all.contains("number compared"),
                "a number on the left:\n" + all);
        assertTrue(all.contains("Sample.java:9 "), "a hexadecimal code:\n" + all);
    }

    private record Rule(String message, Pattern pattern) {
    }

    private static List<String> scan(List<SourceFile> files) {
        List<String> findings = new ArrayList<>();

        for (SourceFile file : files) {
            String skeleton = ArgbColorLintTest.skeleton(file.text());

            for (Rule rule : RULES) {
                Matcher match = rule.pattern().matcher(skeleton);
                while (match.find()) {
                    findings.add(file.displayName() + ":" + ArgbColorLintTest.lineOf(skeleton, match.start())
                            + " " + rule.message() + " (\"" + match.group().strip() + "\")");
                }
            }
        }

        return findings;
    }
}
