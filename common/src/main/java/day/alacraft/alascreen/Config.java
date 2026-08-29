package day.alacraft.alascreen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

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

    /** Where screenshots are uploaded. Point it at a self-hosted instance if you run one. */
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
     * Downscale anything wider or taller than this before uploading. 0 keeps the
     * original size.
     *
     * <p>Aimed at the huge-resolution mods: an 8K panorama is tens of megabytes
     * and will be refused by the size cap anyway, so shrinking beats a failed
     * upload. The default is 0 because a normal screenshot must never be
     * silently degraded.
     */
    public int maxUploadSide = 0;

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
                config.maxUploadSide = integer(json, "maxUploadSide", config.maxUploadSide);
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
        json.addProperty("maxUploadSide", maxUploadSide);

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, GSON.toJson(json) + System.lineSeparator(), StandardCharsets.UTF_8);
        PrivateFiles.restrictToOwner(file);
    }

    /** True when uploads should be attached to an account. */
    public boolean hasApiToken() {
        return apiToken != null && !apiToken.isBlank();
    }

    /**
     * Repair values that would otherwise fail far from their cause — an empty
     * base URL surfacing as a confusing HTTP error, a negative side length as an
     * exception inside the scaler.
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

        // 0 means "leave it alone". Anything positive is clamped to a sane floor:
        // a 16-pixel "screenshot" is a mistake, not a preference.
        if (maxUploadSide != 0) {
            maxUploadSide = Math.max(256, Math.min(15360, maxUploadSide));
        }
    }

    private static String string(JsonObject json, String key, String fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback;
    }

    private static int integer(JsonObject json, String key, int fallback) {
        try {
            return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsInt() : fallback;
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return fallback;
        }
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
