package day.alacraft.alascreen.api;

import com.google.gson.JsonObject;

import java.util.Optional;

/**
 * A screenshot that now lives on the site.
 *
 * @param id          the short public id, e.g. {@code aB3xY9kM}
 * @param url         the page a human should open — already in the player's locale
 * @param rawUrl      the image itself, for anyone who wants the file rather than the page
 * @param deleteToken the one-time token that proves ownership; the only thing
 *                    standing between the uploader and everyone else, so it is
 *                    stored locally and never shown in chat
 * @param width       pixels, as the site recorded them after its own processing
 * @param height      pixels
 * @param bytes       the stored size, which is not the uploaded size — the site
 *                    re-encodes to strip metadata
 */
public record UploadResult(
        String id,
        String url,
        String rawUrl,
        String deleteToken,
        int width,
        int height,
        long bytes) {

    public static UploadResult from(JsonObject json) {
        return new UploadResult(
                Json.string(json, "id"),
                Json.string(json, "url"),
                Json.string(json, "raw_url"),
                Json.string(json, "delete_token"),
                (int) Json.longValue(json, "width", 0),
                (int) Json.longValue(json, "height", 0),
                Json.longValue(json, "bytes", 0));
    }

    public Optional<String> deleteTokenIfPresent() {
        return deleteToken == null || deleteToken.isBlank() ? Optional.empty() : Optional.of(deleteToken);
    }

    /** The link without its scheme — what goes in a chat line meant to be read and retyped. */
    public String displayUrl() {
        return url == null ? "" : url.replaceFirst("^https?://", "");
    }
}
