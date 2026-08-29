package day.alacraft.alascreen.api;

import com.google.gson.JsonObject;

/**
 * What the site will currently accept.
 *
 * <p>Read once at startup rather than discovered during the first upload, so a
 * shot that is too big is refused locally — with a message that says by how
 * much — instead of after the player has waited for eight megabytes to travel.
 *
 * <p>Read from the site rather than compiled in, because the whole reason these
 * are published is that they change. Ala Logger shipped a first version whose
 * writer used hardcoded caps while its reader fetched them; the two agreed on
 * the day, which is exactly why nobody noticed.
 */
public record Limits(long maxBytes, long maxPixels, int retentionDays) {

    /**
     * Conservative values for when the site cannot be asked.
     *
     * <p>Deliberately not generous: guessing high would let the mod spend a
     * minute uploading something that gets refused on arrival.
     */
    public static Limits fallback() {
        return new Limits(10L * 1024 * 1024, 26_000_000L, 90);
    }

    /**
     * Read the site's {@code /screenshots/limits} answer.
     *
     * <p><b>The field names are camelCase and the retention is in seconds.</b>
     * Both are the house convention for {@code /limits} across this API — the
     * Log Checker publishes {@code storageTime}, {@code maxLength},
     * {@code maxLines} the same way, inherited from the format the ecosystem
     * already speaks. The first version of this method guessed
     * {@code max_bytes} / {@code retention_days}, matching the snake_case that
     * the UPLOAD response uses, and every field silently fell back to its
     * default. It looked correct because the defaults and the real values are
     * the same number today — which is precisely the failure the fallback was
     * supposed to prevent, since limits are fetched over the network exactly so
     * they can change.
     */
    public static Limits from(JsonObject json) {
        Limits fallback = fallback();

        long seconds = Json.longValue(json, "storageTime", (long) fallback.retentionDays() * 86_400L);

        return new Limits(
                Json.longValue(json, "maxBytes", fallback.maxBytes()),
                Json.longValue(json, "maxPixels", fallback.maxPixels()),
                (int) Math.max(1, seconds / 86_400L));
    }

    /** True when an image of this size in bytes would be refused. */
    public boolean rejectsBytes(long bytes) {
        return maxBytes > 0 && bytes > maxBytes;
    }

    /** True when an image of these dimensions would be refused on pixel count. */
    public boolean rejectsPixels(int width, int height) {
        return maxPixels > 0 && (long) width * height > maxPixels;
    }
}
