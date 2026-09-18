package day.alacraft.alascreen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import day.alacraft.alascreen.api.AlaScreenApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The mod's settings, as a flat JSON file the player can read and edit.
 *
 * <p>Flat and small on purpose: a "press a key, get a link" tool that needs
 * configuring has already failed. Everything here either points the mod at a
 * different site, or turns off a behaviour somebody might reasonably object to.
 *
 * <p>Missing keys fall back to the default and the file is rewritten with them,
 * so upgrading the mod never leaves a stale file half-read.
 */
public final class Config {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Bumped only when a key changes meaning, not when one is added. */
    public static final int SCHEMA_VERSION = 1;

    /**
     * Where screenshots are uploaded, exactly as the player wrote it. Point it at
     * a self-hosted instance if you run one.
     *
     * <p>Never replaced by the default, not even when it cannot be used - the
     * address actually used is {@link #effectiveApiBaseUrl()}.
     */
    public String apiBaseUrl = AlaScreen.DEFAULT_API_BASE_URL;

    /**
     * Optional personal API token from https://alacraft.day/profile.
     *
     * <p>With it, uploads are attached to that account, appear under "my
     * screenshots" and get a private rate limit instead of one shared with
     * everyone behind the same IP address.
     */
    public String apiToken = "";

    /**
     * Show the shot and ask before anything leaves the machine.
     *
     * <p>Default true, and this is the mod's one real safety feature. There are
     * no redaction rules for pixels: a screenshot can carry chat, coordinates,
     * a server address or someone else's name, and no regex will find them.
     * The only honest protection is letting a human look at the frame first.
     *
     * <p>Turning it off is supported — some people take fifty shots an evening —
     * but it is a deliberate choice, not the default.
     */
    public boolean confirmBeforeUpload = true;

    /** Put the link on the system clipboard as soon as it arrives. */
    public boolean copyLinkToClipboard = true;

    /**
     * Also keep the captured shot in the game's {@code screenshots/} folder.
     *
     * <p>On by default because pressing a screenshot key and ending up with no
     * file would astonish anyone.
     */
    public boolean saveLocalCopy = true;

    /**
     * Remember uploaded ids and their delete tokens across restarts.
     *
     * <p>Kept in memory, they would not survive a restart, and the ability to
     * delete your own upload would go with them. Surviving is the whole feature.
     */
    public boolean persistHistory = true;

    /**
     * Show a small plaque with the shot after vanilla saves a screenshot.
     *
     * <p>On by default: without it F2 leaves only a chat line, and acting on that
     * line means opening the chat and reaching for the mouse. The plaque shows
     * what was captured and names the key that shares it.
     *
     * <p>One flag rather than several. The market for this kind of notification
     * asks to remove it, not to move it — so there is no position setting and no
     * duration setting either: the duration is the game's own, including the
     * player's Notification Time accessibility multiplier.
     */
    public boolean toastAfterCapture = true;

    public static Config load(Path file) {
        Config config = new Config();

        try {
            if (Files.exists(file)) {
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                JsonObject json = JsonParser.parseString(raw).getAsJsonObject();

                config.apiBaseUrl = string(json, "apiBaseUrl", config.apiBaseUrl);
                config.apiToken = string(json, "apiToken", config.apiToken);
                config.confirmBeforeUpload = bool(json, "confirmBeforeUpload", config.confirmBeforeUpload);
                config.copyLinkToClipboard = bool(json, "copyLinkToClipboard", config.copyLinkToClipboard);
                config.saveLocalCopy = bool(json, "saveLocalCopy", config.saveLocalCopy);
                config.persistHistory = bool(json, "persistHistory", config.persistHistory);
                config.toastAfterCapture = bool(json, "toastAfterCapture", config.toastAfterCapture);
            }

            config.normalise();
            config.save(file);
        } catch (Exception e) {
            // A broken config must not stop the game from starting. Defaults are
            // safe and the file is left untouched so the player can fix it.
            AlaScreen.LOGGER.warn("Could not read {} ({}). Using defaults; the file was left as it is.",
                    file, e.getMessage());
            config.normalise();
        }

        if (AlaScreenApi.usableBaseUrl(config.apiBaseUrl).isEmpty()) {
            // Every start, not once: the file keeps the broken value, so the
            // complaint has to keep pace with it. An error that stops after the
            // first restart is an error nobody acts on.
            AlaScreen.LOGGER.error("apiBaseUrl in the config is not a usable URL (\"{}\"), so {} is used "
                            + "instead. It has to start with https:// (or http://). The file keeps your value, "
                            + "so it can be corrected.",
                    config.apiBaseUrl, AlaScreen.DEFAULT_API_BASE_URL);
        }

        return config;
    }

    public void save(Path file) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("apiBaseUrl", apiBaseUrl);
        json.addProperty("apiToken", apiToken);
        json.addProperty("confirmBeforeUpload", confirmBeforeUpload);
        json.addProperty("copyLinkToClipboard", copyLinkToClipboard);
        json.addProperty("saveLocalCopy", saveLocalCopy);
        json.addProperty("persistHistory", persistHistory);
        json.addProperty("toastAfterCapture", toastAfterCapture);

        // Composed beside the file and moved over it, owner-only from the moment
        // it exists. This file holds an API token: writing it in place left a
        // truncated config after a crash mid-write, and tightening the
        // permissions afterwards left the token readable by every account on the
        // machine for as long as the write took.
        AtomicFiles.writePrivate(file, GSON.toJson(json) + System.lineSeparator());
    }

    /** True when uploads should be attached to an account. */
    public boolean hasApiToken() {
        return apiToken != null && !apiToken.isBlank();
    }

    /**
     * The address uploads actually go to: {@link #apiBaseUrl}, or the default
     * standing in for it when it cannot be used.
     *
     * <p>Computed, never stored. The first version put the default INTO
     * {@code apiBaseUrl} when the player's address could not be used, and the
     * config is saved mid-game when "do not ask again" is ticked - so a
     * self-hoster's address, typo and all, was overwritten with ours. The file
     * then read as valid, the error at startup never came back, and their
     * screenshots went to alacraft.day in silence. A fallback may be used; it
     * must never be written.
     *
     * <p>Decided by {@link AlaScreenApi#usableBaseUrl}, the rule the client's
     * builder applies too, so this never hands the builder an address it would
     * throw on.
     */
    public String effectiveApiBaseUrl() {
        return AlaScreenApi.usableBaseUrl(apiBaseUrl).orElse(AlaScreen.DEFAULT_API_BASE_URL);
    }

    /**
     * Repair values that would otherwise fail far from their cause — an empty
     * base URL surfacing as a confusing HTTP error.
     *
     * <p>Repair, not replace: an address that is present but unusable is left
     * as written, so the player still has their own value to correct.
     */
    private void normalise() {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = AlaScreen.DEFAULT_API_BASE_URL;
        }
        apiBaseUrl = apiBaseUrl.trim();
        while (apiBaseUrl.endsWith("/")) {
            apiBaseUrl = apiBaseUrl.substring(0, apiBaseUrl.length() - 1);
        }

        apiToken = apiToken == null ? "" : apiToken.trim();
    }

    private static String string(JsonObject json, String key, String fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback;
    }

    /**
     * Read a boolean, and only a boolean.
     *
     * <p>The obvious {@code getAsBoolean()} is a trap here, and an expensive one.
     * Gson does not throw when the value is a string: it hands the text to
     * {@code Boolean.parseBoolean}, which answers {@code false} for everything
     * that is not literally "true". So a config saying
     * {@code "confirmBeforeUpload": "yes"} — a plausible thing for a person to
     * type — silently turned OFF the confirmation screen, the one feature
     * standing between a private screenshot and a public URL. The value was
     * wrong, the fallback was correct, and the fallback never ran because
     * nothing failed.
     *
     * <p>So the type is checked rather than the parse: anything that is not a
     * JSON boolean is treated as absent and gets the default.
     */
    private static boolean bool(JsonObject json, String key, boolean fallback) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()) {
            return fallback;
        }

        JsonPrimitive primitive = json.get(key).getAsJsonPrimitive();

        return primitive.isBoolean() ? primitive.getAsBoolean() : fallback;
    }
}
