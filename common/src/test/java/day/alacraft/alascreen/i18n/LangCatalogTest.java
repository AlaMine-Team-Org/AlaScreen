package day.alacraft.alascreen.i18n;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import day.alacraft.alascreen.api.ApiErrorCode;
import day.alacraft.alascreen.clipboard.CopyOutcome;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The translations, checked as a set rather than as fourteen separate files.
 *
 * <p>A translation key is a string literal. The compiler never looks at it, and
 * the game does not fail on a missing one — it prints the key. So
 * {@code alascreen.button.copy_image} reaches a player as the literal text
 * "alascreen.button.copy_image", and only in the language nobody on the team
 * reads. Nothing else in this project catches that: the artifact gate checks
 * that the fourteen files are <em>inside the jar</em>, not what is inside them.
 *
 * <p>Two things are therefore checked here — that every locale carries the same
 * keys as English, and that every key the code actually asks for exists at all.
 */
class LangCatalogTest {

    /**
     * The fourteen locales the mod ships, named rather than discovered.
     *
     * <p>Named on purpose: a discovered list would silently accept a fifteenth file
     * that nobody has reviewed, and silently accept the disappearance of one.
     * Adding a locale means editing this line, which is the point.
     */
    private static final List<String> LOCALES = List.of(
            "en_us", "ru_ru", "uk_ua", "de_de", "fr_fr", "es_es", "pt_br", "ja_jp", "zh_cn",
            "pl_pl", "tr_tr", "cs_cz", "th_th", "ko_kr");

    private static final Path LANG = Path.of("src/main/resources/assets/alascreen/lang");

    /** Where the mod's own code lives, relative to this module. */
    private static final List<Path> SOURCES = List.of(
            Path.of("src/main/java"),
            Path.of("../common-mc/src/main/java"),
            // The per-Minecraft-line copies (src/mc/<line>/java) - every line, not
            // just the one being built, so a twin cannot hide from the check.
            Path.of("../common-mc/src/mc"),
            Path.of("../fabric/src/main/java"),
            Path.of("../neoforge/src/main/java"));

    /**
     * A translation key as it appears in Java source.
     *
     * <p>Two segments are enough. The first version demanded three, to keep the
     * config file name {@code alascreen.json} out — and the mod has exactly one
     * two-segment key, {@code alascreen.prefix}, which sits at the head of every
     * single line this mod prints. The gate could not see it: deleting that key
     * from all fourteen files left this check green. File names are excluded by
     * their suffix in {@code keysIn} below, which is what was doing the work.
     */
    private static final Pattern KEY_IN_SOURCE = Pattern.compile(
            "\"((?:alascreen|key)\\.[a-z0-9_]+(?:\\.[a-z0-9_]+)*)\"");

    @Test
    void theFourteenLocalesAreAllThereAndNothingElseIs() throws IOException {
        Set<String> onDisk = new TreeSet<>();

        try (Stream<Path> files = Files.list(LANG)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> onDisk.add(path.getFileName().toString().replace(".json", "")));
        }

        assertEquals(new TreeSet<>(LOCALES), onDisk,
                "the shipped locales and the files on disk have drifted apart");
    }

    @Test
    void everyLocaleCarriesExactlyTheEnglishKeys() throws IOException {
        Set<String> english = keysOf("en_us");

        assertTrue(english.size() > 50, "en_us looks truncated: " + english.size() + " keys");

        for (String locale : LOCALES) {
            Set<String> keys = keysOf(locale);

            Set<String> missing = new TreeSet<>(english);
            missing.removeAll(keys);

            Set<String> extra = new TreeSet<>(keys);
            extra.removeAll(english);

            assertTrue(missing.isEmpty(), locale + " is missing " + missing);
            assertTrue(extra.isEmpty(), locale + " has keys English does not: " + extra);
        }
    }

    @Test
    void everyKeyTheCodeAsksForExists() throws IOException {
        Set<String> english = keysOf("en_us");
        Set<String> used = keysUsedInCode();

        assertTrue(used.size() > 20,
                "the scanner found only " + used.size() + " keys — it has stopped finding");

        Set<String> unknown = new TreeSet<>(used);
        unknown.removeAll(english);

        assertTrue(unknown.isEmpty(),
                "these keys are used in code but exist in no language file, so they reach the "
                        + "player as raw text: " + unknown);
    }

    @Test
    void keysBuiltFromEnumNamesExistToo() throws IOException {
        Set<String> english = keysOf("en_us");

        // Fourteen of the mod's messages are addressed as
        // "alascreen.error." + name().toLowerCase(): no literal, so the source
        // scan above cannot see them. A new constant in either enum would ship
        // green and reach the player as raw text — which is precisely what
        // ApiErrorCode's own documentation promises cannot happen.
        for (ApiErrorCode code : ApiErrorCode.values()) {
            assertTrue(english.contains(code.messageKey()),
                    "ApiErrorCode." + code.name() + " asks for " + code.messageKey()
                            + ", which no language file has");
        }

        for (CopyOutcome outcome : CopyOutcome.values()) {
            assertTrue(english.contains(outcome.messageKey()),
                    "CopyOutcome." + outcome.name() + " asks for " + outcome.messageKey()
                            + ", which no language file has");
        }
    }

    @Test
    void theScannerCanActuallyFindAKey() {
        // The check above passes when the scanner finds nothing at all, so the
        // scanner is asked to prove it works on a line shaped like the real ones.
        String sample = """
                tell(minecraft, ChatFormat.info("alascreen.chat.copied_image"));
                Path config = directory.resolve("alascreen.json");
                """;

        Set<String> found = keysIn(sample);

        assertEquals(Set.of("alascreen.chat.copied_image"), found,
                "the scanner must find the message key and must not mistake the file name for one");
    }

    private static Set<String> keysOf(String locale) throws IOException {
        String text = Files.readString(LANG.resolve(locale + ".json"), StandardCharsets.UTF_8);
        JsonObject json = JsonParser.parseString(text).getAsJsonObject();

        return new TreeSet<>(json.keySet());
    }

    private static Set<String> keysUsedInCode() throws IOException {
        Set<String> keys = new LinkedHashSet<>();

        for (Path root : SOURCES) {
            if (!Files.isDirectory(root)) {
                continue;
            }

            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    keys.addAll(keysIn(Files.readString(file, StandardCharsets.UTF_8)));
                }
            }
        }

        return keys;
    }

    private static Set<String> keysIn(String source) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = KEY_IN_SOURCE.matcher(source);

        while (matcher.find()) {
            String key = matcher.group(1);

            // Resource paths wear the same shape as message keys.
            if (!key.endsWith(".json") && !key.endsWith(".png") && !key.endsWith(".toml")) {
                keys.add(key);
            }
        }

        return keys;
    }
}
