package day.alacraft.alascreen.gui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every colour this mod hands to the GUI carries its alpha byte.
 *
 * <p>In 26.2 a GUI colour is ARGB, and the alpha is not implied. The text path
 * says so in its first instruction: {@code GuiGraphicsExtractor.text(Font,
 * FormattedCharSequence, int, int, int, boolean)} begins with
 * {@code if (ARGB.alpha(color) == 0) return;} (read from the 26.2 jar with
 * javap), and {@code centeredText} and {@code textWithWordWrap} both end up
 * there. So {@code 0xFFFFFF}, the way a colour was written for years, is white
 * at zero opacity: the text is simply not drawn. Nothing throws, nothing is
 * logged.
 *
 * <p>That shipped twice. The toast lost its words first, and was caught in the
 * dev client. Then the confirmation screen went out in 0.1.0 to 0.1.2 with its
 * title, its warning and its details line all invisible - on the one screen
 * whose whole job is to be read before a picture leaves the machine. The rule
 * had been written down in between. A rule is not a check; this is the check,
 * and it covers the kind of mistake rather than the two places it happened.
 *
 * <p>How: in every source file of the mod, find the calls made on a variable
 * declared as {@code GuiGraphicsExtractor}, pick the colour argument out of the
 * methods that take one, and resolve it - a literal, or an {@code int} constant
 * followed through other constants. A colour whose top byte is zero fails. So
 * does a colour the scanner cannot resolve: a gate that cannot judge has to say
 * so rather than pass.
 */
class ArgbColorLintTest {

    /** Where the mod's own code lives, relative to this module - the list LangCatalogTest reads too. */
    private static final List<Path> SOURCES = List.of(
            Path.of("src/main/java"),
            Path.of("../common-mc/src/main/java"),
            Path.of("../fabric/src/main/java"),
            Path.of("../neoforge/src/main/java"));

    /**
     * Which arguments are colours, by method name and then by argument count.
     *
     * <p>Read from the 26.2 jar with {@code javap -c}, following each parameter
     * to the instruction that consumes it as a colour rather than guessing from
     * its position. Methods that take no colour - blit, blitSprite, item, pose -
     * are not listed and not checked.
     *
     * <p>One ambiguity, taken knowingly: {@code fill} has two six-argument
     * overloads. The one taking a RenderPipeline and five ints has its colour
     * last; the one taking a RenderPipeline and a TextureSetup has no colour at
     * all (it passes -1 itself). This table assumes the first. The mod uses
     * neither, and a call to the second would be reported, not waved through.
     */
    private static final Map<String, Map<Integer, int[]>> COLOUR_ARGUMENTS = Map.of(
            "text", Map.of(5, new int[] {4}, 6, new int[] {4}),
            "centeredText", Map.of(5, new int[] {4}),
            "textWithWordWrap", Map.of(6, new int[] {5}, 7, new int[] {5}),
            "textWithBackdrop", Map.of(6, new int[] {5}),
            "fill", Map.of(5, new int[] {4}, 6, new int[] {5}),
            "fillGradient", Map.of(6, new int[] {4, 5}),
            "horizontalLine", Map.of(4, new int[] {3}),
            "verticalLine", Map.of(4, new int[] {3}),
            "outline", Map.of(5, new int[] {4}));

    private static final Pattern RECEIVER = Pattern.compile("\\bGuiGraphicsExtractor\\s+([A-Za-z_$][\\w$]*)");

    private static final Pattern CONSTANT_DECLARATION = Pattern.compile(
            "\\b(?:static\\s+final|final\\s+static)\\s+int\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*([^;]+);");

    private static final Pattern HEX = Pattern.compile("(-?)0[xX]([0-9A-Fa-f_]+)[lL]?");

    private static final Pattern DECIMAL = Pattern.compile("-?[0-9][0-9_]*[lL]?");

    /** {@code NAME}, {@code this.NAME} or {@code SomeClass.NAME}. */
    private static final Pattern CONSTANT_REFERENCE = Pattern.compile(
            "(?:this\\.|([A-Z][\\w$]*)\\.)?([A-Za-z_$][\\w$]*)");

    @Test
    void everyColourTheModDrawsWithHasItsAlpha() throws IOException {
        // The scanner proves it can find before its silence is taken as a
        // verdict. Here as well as in its own test, so running this test alone
        // cannot skip the proof.
        assertScannerWorks();

        Report report = scan(modSources());

        assertTrue(report.checked() > 0,
                "no colour argument was checked anywhere in the mod - the scanner has stopped finding");
        assertTrue(report.findings().isEmpty(),
                "colours drawn without their alpha byte are invisible in 26.2 - write them with all eight "
                        + "digits, 0xFFRRGGBB:\n  " + String.join("\n  ", report.findings()));
    }

    @Test
    void theScannerFindsEveryShapeOfTheMistake() {
        assertScannerWorks();
    }

    @Test
    void theScannerDoesNotCryWolf() {
        String source = """
                class Sample {
                    private static final int TITLE = 0xFFFFFFFF;
                    private static final int HINT = 0xFFA0A0A0;
                    private static final int FRAME = TITLE;

                    // graphics.text(font, line, 0, 0, 0xFFFFFF);
                    /* graphics.fill(0, 0, 1, 1, 0x3A3A3A); */
                    private final String decoy = "graphics.fill(0, 0, 1, 1, 0x3A3A3A)";

                    void draw(GuiGraphicsExtractor graphics, Font font, Component title, int[] pixels) {
                        graphics.text(font, title, 18, 7, TITLE, false);
                        graphics.text(font, "a(b, c", 18, 19, HINT);
                        graphics.text(font, title, 18, 31, -1);
                        graphics.fill(0, 0, 1, 1, FRAME);
                        graphics.centeredText(font, title, 0, 0, ARGB.opaque(0x3A3A3A));
                        graphics.blit(TEXTURE, 0, 0, 1, 1, 0.0F, 1.0F, 0.0F, 1.0F);
                        java.util.Arrays.fill(pixels, 0);
                        builder.text(font, title, 0, 0, 0xFFFFFF);
                    }
                }
                """;

        Report report = scan(List.of(new SourceFile("Sample.java", "Sample", source)));

        assertEquals(List.of(), report.findings(),
                "correct colours, comments, strings, colourless calls and other receivers must all pass");
        assertEquals(5, report.checked(), "five colours are drawn on the GuiGraphicsExtractor in the sample");
    }

    /**
     * One sample per way the mistake has been, or could be, written - through a
     * constant, through a constant that aliases another, as a literal broken over
     * two lines, as a decimal, and as something the scanner cannot see through.
     */
    private static void assertScannerWorks() {
        String source = """
                class Sample {
                    private static final int TEXT = 0xFFFFFF;
                    private static final int ALIAS = TEXT;

                    void draw(GuiGraphicsExtractor graphics, Font font, Component title, int width, int dynamic) {
                        graphics.centeredText(font, title, width / 2, 18, TEXT);
                        graphics.text(font, "a(b, c)", 0, 0,
                                0xA0A0A0, false);
                        graphics.fill(0, 0, 1, 1, 16777215);
                        graphics.textWithWordWrap(font, title, 0, 0, width, ALIAS);
                        graphics.text(font, title, 0, 0, dynamic);
                        graphics.text(font, title);
                    }
                }
                """;

        Report report = scan(List.of(new SourceFile("Sample.java", "Sample", source)));
        String all = String.join("\n", report.findings());

        assertEquals(6, report.findings().size(), "each broken call must be reported once:\n" + all);
        assertTrue(all.contains("Sample.java:6 centeredText") && all.contains("TEXT"),
                "a constant without alpha, and the line it is used on:\n" + all);
        assertTrue(all.contains("Sample.java:7 text") && all.contains("0xA0A0A0"),
                "a literal on a wrapped line, past a string holding brackets and commas:\n" + all);
        assertTrue(all.contains("16777215"), "the same mistake written in decimal:\n" + all);
        assertTrue(all.contains("ALIAS"), "a constant that only points at another:\n" + all);
        assertTrue(all.contains("dynamic") && all.contains("cannot be resolved"),
                "a colour the scanner cannot see through is reported, not passed:\n" + all);
        assertTrue(all.contains("text with 2 arguments"),
                "an overload the table does not know is reported, not ignored:\n" + all);
    }

    record SourceFile(String displayName, String className, String text) {
    }

    private record Report(int checked, List<String> findings) {
    }

    static List<SourceFile> modSources() throws IOException {
        List<SourceFile> files = new ArrayList<>();

        for (Path root : SOURCES) {
            assertTrue(Files.isDirectory(root), "a source root is missing: " + root.toAbsolutePath().normalize());

            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    String name = path.getFileName().toString();

                    files.add(new SourceFile(
                            root.relativize(path).toString().replace('\\', '/'),
                            name.substring(0, name.length() - ".java".length()),
                            Files.readString(path, StandardCharsets.UTF_8)));
                }
            }
        }

        return files;
    }

    private static Report scan(List<SourceFile> files) {
        Map<String, String> skeletons = new HashMap<>();
        Map<String, Map<String, String>> constants = new HashMap<>();

        for (SourceFile file : files) {
            String skeleton = skeleton(file.text());
            skeletons.put(file.displayName(), skeleton);

            Matcher declaration = CONSTANT_DECLARATION.matcher(skeleton);
            while (declaration.find()) {
                constants.computeIfAbsent(file.className(), key -> new HashMap<>())
                        .put(declaration.group(1), declaration.group(2).strip());
            }
        }

        int checked = 0;
        List<String> findings = new ArrayList<>();

        for (SourceFile file : files) {
            String skeleton = skeletons.get(file.displayName());

            Set<String> receivers = new LinkedHashSet<>();
            Matcher receiver = RECEIVER.matcher(skeleton);
            while (receiver.find()) {
                receivers.add(receiver.group(1));
            }

            for (String name : receivers) {
                Matcher call = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "\\s*\\.\\s*("
                        + String.join("|", COLOUR_ARGUMENTS.keySet()) + ")\\s*\\(").matcher(skeleton);

                while (call.find()) {
                    String method = call.group(1);
                    List<String> arguments = arguments(skeleton, call.end() - 1);
                    String where = file.displayName() + ":" + lineOf(skeleton, call.start()) + " " + method;
                    int[] colours = COLOUR_ARGUMENTS.get(method).get(arguments.size());

                    if (colours == null) {
                        findings.add(where + " - GuiGraphicsExtractor." + method + " with " + arguments.size()
                                + " arguments is not in COLOUR_ARGUMENTS; read the overload with javap and add it");
                        continue;
                    }

                    for (int index : colours) {
                        String expression = arguments.get(index).strip();

                        if (isExplicit(expression)) {
                            checked++;
                            continue;
                        }

                        OptionalLong value = valueOf(expression, file.className(), constants, 0);

                        if (value.isEmpty()) {
                            findings.add(where + " - the colour \"" + expression + "\" cannot be resolved to a "
                                    + "value; use a literal, an int constant or ARGB.*");
                            continue;
                        }

                        checked++;
                        int argb = (int) value.getAsLong();

                        if ((argb >>> 24) == 0) {
                            findings.add(where + " - the colour \"" + expression + "\" is "
                                    + String.format("0x%08X", argb) + ": its alpha is zero, so it is not drawn");
                        }
                    }
                }
            }
        }

        return new Report(checked, findings);
    }

    /** Built through {@code ARGB}, the alpha is written by hand and is the author's decision. */
    private static boolean isExplicit(String expression) {
        String unwrapped = unwrap(expression);
        return unwrapped.startsWith("ARGB.") || unwrapped.startsWith("net.minecraft.util.ARGB.");
    }

    private static OptionalLong valueOf(String expression, String owner,
                                        Map<String, Map<String, String>> constants, int depth) {
        String unwrapped = unwrap(expression);

        Matcher hex = HEX.matcher(unwrapped);
        if (hex.matches()) {
            long value = Long.parseUnsignedLong(hex.group(2).replace("_", ""), 16);
            return OptionalLong.of(hex.group(1).isEmpty() ? value : -value);
        }

        if (DECIMAL.matcher(unwrapped).matches()) {
            return OptionalLong.of(Long.parseLong(unwrapped.replace("_", "").replaceAll("[lL]$", "")));
        }

        Matcher reference = CONSTANT_REFERENCE.matcher(unwrapped);
        if (reference.matches() && depth < 8) {
            String file = reference.group(1) != null ? reference.group(1) : owner;
            String initialiser = constants.getOrDefault(file, Map.of()).get(reference.group(2));

            if (initialiser != null) {
                return valueOf(initialiser, file, constants, depth + 1);
            }
        }

        return OptionalLong.empty();
    }

    /** Drops redundant brackets and an {@code (int)} cast, which change nothing about the value. */
    private static String unwrap(String expression) {
        String current = expression.strip();

        while (true) {
            if (current.startsWith("(int)")) {
                current = current.substring("(int)".length()).strip();
            } else if (current.startsWith("(") && closing(current, 0) == current.length() - 1) {
                current = current.substring(1, current.length() - 1).strip();
            } else {
                return current;
            }
        }
    }

    /** The top-level arguments of the call whose opening bracket is at {@code open}. */
    private static List<String> arguments(String skeleton, int open) {
        List<String> arguments = new ArrayList<>();
        int depth = 0;
        int start = open + 1;

        for (int i = open + 1; i < skeleton.length(); i++) {
            char c = skeleton.charAt(i);

            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if ((c == ')' || c == ']' || c == '}') && depth > 0) {
                depth--;
            } else if (c == ')') {
                String last = skeleton.substring(start, i);
                if (!last.isBlank() || !arguments.isEmpty()) {
                    arguments.add(last);
                }
                return arguments;
            } else if (c == ',' && depth == 0) {
                arguments.add(skeleton.substring(start, i));
                start = i + 1;
            }
        }

        throw new IllegalStateException("unbalanced brackets after offset " + open);
    }

    private static int closing(String text, int open) {
        int depth = 0;

        for (int i = open; i < text.length(); i++) {
            if (text.charAt(i) == '(') {
                depth++;
            } else if (text.charAt(i) == ')' && --depth == 0) {
                return i;
            }
        }

        return -1;
    }

    static int lineOf(String text, int offset) {
        int line = 1;

        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }

        return line;
    }

    /**
     * The source with its comments, and the insides of its string and char
     * literals, replaced by spaces.
     *
     * <p>Replaced rather than removed, so every character keeps its offset and a
     * finding names the real line. A commented-out call is not a call, and a
     * bracket inside {@code "a(b"} is not a bracket - both learnt the hard way by
     * the loader parity check, whose scanner once counted a commented-out call.
     */
    static String skeleton(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int length = source.length();
        int i = 0;

        while (i < length) {
            char c = source.charAt(i);
            char next = i + 1 < length ? source.charAt(i + 1) : '\0';

            if (c == '/' && next == '/') {
                while (i < length && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && next == '*') {
                out.append("  ");
                i += 2;
                while (i < length && !(source.charAt(i) == '*' && i + 1 < length && source.charAt(i + 1) == '/')) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < length) {
                    out.append("  ");
                    i += 2;
                }
            } else if (source.startsWith("\"\"\"", i)) {
                out.append("\"\"\"");
                i += 3;
                while (i < length && !source.startsWith("\"\"\"", i)) {
                    if (source.charAt(i) == '\\' && i + 1 < length) {
                        out.append(source.charAt(i + 1) == '\n' ? " \n" : "  ");
                        i += 2;
                        continue;
                    }
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < length) {
                    out.append("\"\"\"");
                    i += 3;
                }
            } else if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (i < length && source.charAt(i) != c && source.charAt(i) != '\n') {
                    if (source.charAt(i) == '\\' && i + 1 < length) {
                        out.append("  ");
                        i += 2;
                        continue;
                    }
                    out.append(' ');
                    i++;
                }
                if (i < length && source.charAt(i) == c) {
                    out.append(c);
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }

        return out.toString();
    }
}
