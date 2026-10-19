package day.alacraft.alascreen.annotate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CropTest {

    private static final double WIDE = 16.0 / 9.0;

    @Test
    void aDragUpAndLeftGivesTheSameFrameAsDownAndRight() {
        Crop backwards = Crop.spanning(new Stroke.Point(0.8f, 0.7f), new Stroke.Point(0.2f, 0.1f), false, WIDE);

        assertEquals(new Crop(0.2f, 0.1f, 0.8f, 0.7f), backwards);
        assertTrue(backwards.left() < backwards.right());
    }

    @Test
    void shiftMakesASquareInPixelsNotInFractions() {
        // On a 16:9 frame a square in fractions is a wide rectangle on screen.
        Crop square = Crop.spanning(new Stroke.Point(0.1f, 0.1f), new Stroke.Point(0.4f, 0.2f), true, WIDE);

        int[] rect = square.pixels(1920, 1080);
        assertEquals(rect[2], rect[3], "square on a 1920x1080 frame: " + rect[2] + "x" + rect[3]);
        assertEquals(576, rect[2], "the longer drag decides the side: 0.3 of 1920");
    }

    @Test
    void aSquareThatWouldLeaveTheFrameShrinksAndStaysSquare() {
        // Clipping at the border would quietly turn the square into a rectangle.
        Crop square = Crop.spanning(new Stroke.Point(0.5f, 0.8f), new Stroke.Point(0.9f, 1.0f), true, WIDE);

        int[] rect = square.pixels(1920, 1080);
        assertEquals(rect[2], rect[3]);
        assertTrue(square.bottom() <= 1.0f);
        assertEquals(216, rect[3], "limited by the 0.2 of height left below the anchor");
    }

    @Test
    void draggingAnEdgePastTheOppositeOneSwapsThem() {
        Crop start = new Crop(0.3f, 0.3f, 0.6f, 0.6f);

        Crop crossed = start.resized(Crop.Handle.W, new Stroke.Point(0.9f, 0.5f), false, WIDE);

        assertEquals(new Crop(0.6f, 0.3f, 0.9f, 0.6f), crossed);
    }

    @Test
    void aCornerResizesFromTheOppositeCorner() {
        Crop start = new Crop(0.3f, 0.3f, 0.6f, 0.6f);

        assertEquals(new Crop(0.1f, 0.2f, 0.6f, 0.6f),
                start.resized(Crop.Handle.NW, new Stroke.Point(0.1f, 0.2f), false, WIDE));
        assertEquals(new Crop(0.3f, 0.3f, 0.8f, 0.9f),
                start.resized(Crop.Handle.SE, new Stroke.Point(0.8f, 0.9f), false, WIDE));
    }

    @Test
    void movingStopsAtTheEdgeWithoutShrinking() {
        Crop start = new Crop(0.2f, 0.2f, 0.5f, 0.6f);

        Crop moved = start.moved(0.9, -0.9);

        assertEquals(1.0f, moved.right(), 1e-6f);
        assertEquals(0.0f, moved.top(), 1e-6f);
        assertEquals(start.width(), moved.width(), 1e-6f);
        assertEquals(start.height(), moved.height(), 1e-6f);
    }

    @Test
    void cornersWinOverEdgesAndEdgesOverTheInside() {
        // On a small frame the reach of the handles overlaps the inside; if
        // MOVE came first such a frame could never be resized again.
        Crop small = new Crop(0.40f, 0.40f, 0.42f, 0.42f);
        double reach = 0.01;

        assertEquals(Crop.Handle.NW, small.handleAt(0.40, 0.40, reach, reach));
        assertEquals(Crop.Handle.SE, small.handleAt(0.42, 0.42, reach, reach));

        Crop big = new Crop(0.2f, 0.2f, 0.8f, 0.8f);
        assertEquals(Crop.Handle.N, big.handleAt(0.5, 0.205, reach, reach));
        assertEquals(Crop.Handle.E, big.handleAt(0.795, 0.5, reach, reach));
        assertEquals(Crop.Handle.MOVE, big.handleAt(0.5, 0.5, reach, reach));
        assertNull(big.handleAt(0.05, 0.5, reach, reach));
    }

    @Test
    void pixelsAreNeverEmptyAndStayInsideTheImage() {
        Crop sliver = new Crop(0.999f, 0.999f, 1.0f, 1.0f);

        int[] rect = sliver.pixels(100, 50);

        assertTrue(rect[2] >= 1 && rect[3] >= 1);
        assertTrue(rect[0] + rect[2] <= 100);
        assertTrue(rect[1] + rect[3] <= 50);
    }

    @Test
    void theCutOnTheFullFrameMatchesThePreviewScaledUp() {
        // The preview darkens with pixels(previewSize) and the cut uses
        // pixels(frameSize); at any scale they must name the same region.
        Crop crop = new Crop(0.137f, 0.291f, 0.702f, 0.855f);

        int[] preview = crop.pixels(640, 360);
        int[] frame = crop.pixels(3840, 2160);

        for (int i = 0; i < 4; i++) {
            assertEquals(preview[i] * 6, frame[i], 6, "edge " + i + " within one preview pixel");
        }
    }

    @Test
    void aClickIsTooSmallAndTheWholeFrameIsNoCrop() {
        Crop click = Crop.spanning(new Stroke.Point(0.5f, 0.5f), new Stroke.Point(0.5f, 0.5f), false, WIDE);
        assertTrue(click.tooSmall(1920, 1080));

        // 15 px wide on the full frame: still too small, even though it would
        // be visible on a large preview.
        assertTrue(new Crop(0.0f, 0.0f, 15f / 1920f, 0.5f).tooSmall(1920, 1080));
        assertFalse(new Crop(0.0f, 0.0f, 16f / 1920f, 16f / 1080f).tooSmall(1920, 1080));

        assertTrue(new Crop(0.0f, 0.0f, 1.0f, 1.0f).whole(1920, 1080));
        assertTrue(new Crop(0.0001f, 0.0f, 1.0f, 0.9999f).whole(1920, 1080), "within half a pixel is the whole frame");
        assertFalse(new Crop(0.01f, 0.0f, 1.0f, 1.0f).whole(1920, 1080));
    }

    @Test
    void valuesOutsideTheFrameAreClamped() {
        Crop crop = new Crop(-0.5f, 1.5f, 0.5f, -1.0f);

        assertArrayEquals(new float[]{0.0f, 0.0f, 0.5f, 1.0f},
                new float[]{crop.left(), crop.top(), crop.right(), crop.bottom()});
    }

    @Test
    void markupWithOnlyACropIsNotEmpty() {
        assertTrue(Markup.NONE.isEmpty());
        assertFalse(new Markup(List.of(), Optional.of(new Crop(0.1f, 0.1f, 0.5f, 0.5f))).isEmpty());
    }
}
