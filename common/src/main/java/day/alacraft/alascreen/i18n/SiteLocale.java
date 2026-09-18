package day.alacraft.alascreen.i18n;

import java.util.Locale;

/**
 * Maps a Minecraft language code onto the locale segment the site uses in URLs.
 *
 * <p>This is the whole of the mod's own i18n machinery, and the smallness is the
 * point. Ala Logger carries a full message bundle because it runs on a server
 * and prints to players who do not have it installed — text there has to be
 * resolved before it is sent. Ala Screen is a client mod: whoever reads the
 * message has the mod, so every player-facing string is an ordinary
 * {@code Component.translatable} resolved by the game against
 * {@code assets/alascreen/lang/}. That gives us fourteen languages for free, plus
 * the player's own resource-pack overrides, and leaves only one thing the game
 * cannot answer — which locale to put in the link.
 *
 * <p>It matters because the link is the product. A Russian player handed
 * {@code alacraft.day/en/screenshots/...} lands on an English page describing
 * their own screenshot.
 */
public final class SiteLocale {

    /** The languages the site serves. Ukrainian is {@code uk} there — never {@code ua}. */
    private static final String[][] MAPPINGS = {
            {"ru", "ru"},
            {"uk", "uk"},
            {"de", "de"},
            {"fr", "fr"},
            {"es", "es"},
            {"ja", "ja"},
            {"zh", "zh"},
            {"en", "en"},
    };

    private static final String FALLBACK = "en";

    private SiteLocale() {
    }

    /**
     * The site locale for a Minecraft language code such as {@code ru_ru}.
     *
     * <p>Matched on the language half alone, so the regional variants clients
     * actually report — {@code de_at}, {@code es_mx}, {@code en_gb} — reach the
     * right page instead of falling back to English. That also means
     * {@code zh_tw} lands on the site's Simplified Chinese page: the site has
     * no Traditional variant, and a Simplified page beats an English one for a
     * Chinese-reading player. Anything we do not serve at all ({@code pt_br})
     * gets English, which is the site's own fallback too.
     */
    public static String of(String minecraftLanguage) {
        if (minecraftLanguage == null || minecraftLanguage.isBlank()) {
            return FALLBACK;
        }

        String code = minecraftLanguage.toLowerCase(Locale.ROOT).replace('-', '_').trim();
        String language = code.contains("_") ? code.substring(0, code.indexOf('_')) : code;

        for (String[] mapping : MAPPINGS) {
            if (mapping[0].equals(language)) {
                return mapping[1];
            }
        }

        return FALLBACK;
    }
}
