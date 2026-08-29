package day.alacraft.alascreen.api;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the site accepts, and what we refuse before sending.
 *
 * <p>The refusal is the point: eight megabytes uploaded from a home connection
 * and rejected on arrival costs the player a minute and one of their rate-limit
 * slots, and the answer was knowable before the first byte left.
 */
class LimitsTest {

    @Test
    void readsTheFieldsTheSiteActuallyPublishes() {
        // Copied verbatim from a live GET /api/v1/screenshots/limits. Written
        // this way on purpose: the first version of this test invented
        // snake_case names, agreed with the implementation, and both were wrong
        // together — a matched pair of guesses passes every assertion while the
        // real answer is ignored.
        JsonObject json = new JsonObject();
        json.addProperty("success", true);
        json.addProperty("maxBytes", 10_485_760);
        json.addProperty("maxPixels", 26_000_000);
        json.addProperty("maxMegapixels", 26);
        json.addProperty("storageTime", 7_776_000);

        Limits limits = Limits.from(json);

        assertEquals(10_485_760L, limits.maxBytes());
        assertEquals(26_000_000L, limits.maxPixels());
        assertEquals(90, limits.retentionDays(), "storageTime is seconds, not days");
    }

    @Test
    void snakeCaseNamesAreNotRead() {
        // Guards the direction the bug came from: if someone "tidies" the reader
        // back to snake_case, these values must not be picked up.
        JsonObject json = new JsonObject();
        json.addProperty("max_bytes", 123);
        json.addProperty("max_pixels", 456);

        Limits limits = Limits.from(json);

        assertEquals(Limits.fallback().maxBytes(), limits.maxBytes());
        assertEquals(Limits.fallback().maxPixels(), limits.maxPixels());
    }

    @Test
    void missingFieldsFallBackRatherThanBecomingZero() {
        // Zero would mean "no limit" to the checks below, so a partial answer
        // from an older instance must not silently disable them.
        Limits limits = Limits.from(new JsonObject());

        assertEquals(Limits.fallback().maxBytes(), limits.maxBytes());
        assertEquals(Limits.fallback().maxPixels(), limits.maxPixels());
        assertEquals(Limits.fallback().retentionDays(), limits.retentionDays());
    }

    @Test
    void garbageValuesFallBackToo() {
        JsonObject json = new JsonObject();
        json.addProperty("maxBytes", "lots");

        assertEquals(Limits.fallback().maxBytes(), Limits.from(json).maxBytes());
    }

    @Test
    void rejectsOversizeAndAcceptsTheBoundary() {
        Limits limits = new Limits(1000, 1_000_000, 90);

        assertFalse(limits.rejectsBytes(999));
        assertFalse(limits.rejectsBytes(1000), "the limit itself must be accepted");
        assertTrue(limits.rejectsBytes(1001));
    }

    @Test
    void pixelCheckMultipliesWithoutOverflowing() {
        Limits limits = new Limits(Long.MAX_VALUE, 26_000_000, 90);

        assertFalse(limits.rejectsPixels(1920, 1080));
        assertTrue(limits.rejectsPixels(7680, 4320), "an 8K frame is 33 megapixels");

        // int arithmetic would wrap here and quietly report "fits".
        assertTrue(limits.rejectsPixels(60000, 60000));
    }

    @Test
    void zeroMeansUnlimited() {
        Limits limits = new Limits(0, 0, 90);

        assertFalse(limits.rejectsBytes(Long.MAX_VALUE));
        assertFalse(limits.rejectsPixels(60000, 60000));
    }
}
