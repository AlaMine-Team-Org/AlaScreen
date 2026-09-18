package day.alacraft.alascreen.annotate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RasterizerTest {

    private static final int RED = 0xFFFF5555;
    private static final int BLOCK = 0xFF1E1E1E;

    @Test
    void theCoverUpCoversEveryPixelItWasDrawnOver() {
        // The one tool whose failure is a leak rather than an ugly mark: a
        // coordinate left half-visible at the edge of the block is still read.
        Pixels canvas = new Pixels(200, 100);

        Rasterizer.paint(canvas, stroke(Tool.REDACT, BLOCK, 0.1f, 0.1f, 0.1f, 0.3f, 0.27f));

        for (int y = 10; y < 27; y++) {
            for (int x = 20; x < 60; x++) {
                assertEquals(BLOCK, canvas.get(x, y), "uncovered at " + x + "," + y);
            }
        }
        assertEquals(0, canvas.get(19, 15));
        assertEquals(0, canvas.get(61, 15));
    }

    @Test
    void theCoverUpRoundsOutwardsNotInwards() {
        // A drag that ends mid-pixel must take that pixel, or a one-pixel strip
        // of the thing being hidden survives along the edge.
        Pixels canvas = new Pixels(10, 10);

        Rasterizer.paint(canvas, stroke(Tool.REDACT, BLOCK, 0.1f, 0.25f, 0.25f, 0.55f, 0.55f));

        assertEquals(BLOCK, canvas.get(2, 2));
        assertEquals(BLOCK, canvas.get(5, 5));
        assertEquals(0, canvas.get(6, 6));
    }

    @Test
    void anArrowReachesItsTipAndLeavesTheRestAlone() {
        Pixels canvas = new Pixels(200, 200);

        Rasterizer.paint(canvas, stroke(Tool.ARROW, RED, 0.02f, 0.1f, 0.5f, 0.9f, 0.5f));

        assertEquals(RED, canvas.get(22, 100), "the shaft starts where the drag began");
        assertEquals(RED, canvas.get(177, 100), "the head reaches the point the drag ended on");
        assertTrue(canvas.count(RED) > 0);
        assertEquals(0, canvas.get(100, 10));
        assertEquals(0, canvas.get(100, 190));

        int headWidth = canvas.runAt(170);
        int shaftWidth = canvas.runAt(60);
        assertTrue(headWidth > shaftWidth * 2, "the head is wider than the shaft: " + headWidth + " vs " + shaftWidth);
    }

    @Test
    void aFrameIsAnOutlineNotABlock() {
        Pixels canvas = new Pixels(100, 100);

        Rasterizer.paint(canvas, stroke(Tool.FRAME, RED, 0.04f, 0.2f, 0.2f, 0.8f, 0.8f));

        assertEquals(RED, canvas.get(20, 50));
        assertEquals(RED, canvas.get(79, 50));
        assertEquals(RED, canvas.get(50, 20));
        assertEquals(RED, canvas.get(50, 79));
        assertEquals(0, canvas.get(50, 50), "a frame shows what is inside it");
    }

    @Test
    void aPenLineHasNoGapsBetweenItsPoints() {
        Pixels canvas = new Pixels(100, 100);

        Rasterizer.paint(canvas, stroke(Tool.PEN, RED, 0.02f, 0.1f, 0.1f, 0.5f, 0.1f, 0.5f, 0.9f));

        for (int x = 11; x < 50; x++) {
            assertEquals(RED, canvas.get(x, 10), "gap at " + x);
        }
        for (int y = 11; y < 90; y++) {
            assertEquals(RED, canvas.get(50, y), "gap at " + y);
        }
    }

    @Test
    void thePreviewAndTheFullFrameShowTheSameMark() {
        // The player judges the small picture and the large one is what is sent.
        Stroke arrow = stroke(Tool.ARROW, RED, 0.01f, 0.2f, 0.3f, 0.7f, 0.6f);
        Pixels preview = new Pixels(320, 180);
        Pixels full = new Pixels(3200, 1800);

        Rasterizer.paint(preview, arrow);
        Rasterizer.paint(full, arrow);

        double previewShare = preview.count(RED) / (320.0 * 180.0);
        double fullShare = full.count(RED) / (3200.0 * 1800.0);
        assertEquals(previewShare, fullShare, previewShare * 0.25, "same share of the picture at either size");

        for (int y = 0; y < 180; y++) {
            for (int x = 0; x < 320; x++) {
                if (preview.get(x, y) == RED && isInterior(preview, x, y)) {
                    assertEquals(RED, full.get(x * 10 + 5, y * 10 + 5), "the full frame misses " + x + "," + y);
                }
            }
        }
    }

    @Test
    void marksThatLeaveThePictureAreClippedNotThrown() {
        Pixels canvas = new Pixels(50, 50);

        Rasterizer.paint(canvas, List.of(
                stroke(Tool.ARROW, RED, 0.5f, 0.0f, 0.0f, 1.0f, 1.0f),
                stroke(Tool.FRAME, RED, 0.5f, 0.0f, 0.0f, 1.0f, 1.0f),
                stroke(Tool.PEN, RED, 0.5f, 1.0f, 1.0f),
                stroke(Tool.REDACT, BLOCK, 0.1f, 0.0f, 0.0f, 1.0f, 1.0f)));

        assertEquals(50 * 50, canvas.count(BLOCK));
    }

    @Test
    void aClickWithoutADragLeavesADotNotACrash() {
        Pixels canvas = new Pixels(50, 50);

        Rasterizer.paint(canvas, stroke(Tool.ARROW, RED, 0.1f, 0.5f, 0.5f, 0.5f, 0.5f));

        assertEquals(RED, canvas.get(25, 25));
    }

    private static boolean isInterior(Pixels canvas, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx;
                int ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= canvas.width() || ny >= canvas.height() || canvas.get(nx, ny) != RED) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Stroke stroke(Tool tool, int argb, float width, float... coordinates) {
        Stroke.Point[] points = new Stroke.Point[coordinates.length / 2];
        for (int i = 0; i < points.length; i++) {
            points[i] = new Stroke.Point(coordinates[i * 2], coordinates[i * 2 + 1]);
        }
        return new Stroke(tool, argb, width, List.of(points));
    }

    private static final class Pixels implements Canvas {

        private final int width;
        private final int height;
        private final int[] data;

        Pixels(int width, int height) {
            this.width = width;
            this.height = height;
            this.data = new int[width * height];
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public void set(int x, int y, int argb) {
            if (x < 0 || y < 0 || x >= width || y >= height) {
                throw new AssertionError("painted outside the canvas at " + x + "," + y);
            }
            data[y * width + x] = argb;
        }

        int get(int x, int y) {
            return data[y * width + x];
        }

        int count(int argb) {
            int n = 0;
            for (int value : data) {
                if (value == argb) {
                    n++;
                }
            }
            return n;
        }

        /** Painted pixels in one column. */
        int runAt(int x) {
            int n = 0;
            for (int y = 0; y < height; y++) {
                if (get(x, y) != 0) {
                    n++;
                }
            }
            return n;
        }
    }
}
