package day.alacraft.alascreen.annotate;

/**
 * The part of the frame that is kept.
 *
 * <p>Fractions of the frame (0..1), like a {@link Stroke}'s points, and for
 * the same reason: the same crop is shown over the small preview and cut out of
 * the full frame, which may be several times larger than the preview's source
 * when the upload had to be scaled down. Pixels are worked out once, for the
 * image actually being cut, by {@link #pixels}.
 *
 * <p>Always normalised: left is never right of right, whichever way the drag
 * went.
 */
public record Crop(float left, float top, float right, float bottom) {

    /**
     * Smallest side, in pixels of the frame being cut, that counts as a crop.
     *
     * <p>A click without a drag would otherwise become a one-pixel picture, and
     * the player would learn that only after it was uploaded.
     */
    public static final int MIN_PIXELS = 16;

    /** Where a drag on the crop frame started: one of eight handles, or inside to move it. */
    public enum Handle {
        NW, N, NE, E, SE, S, SW, W, MOVE
    }

    public Crop {
        float x0 = clamp(Math.min(left, right));
        float x1 = clamp(Math.max(left, right));
        float y0 = clamp(Math.min(top, bottom));
        float y1 = clamp(Math.max(top, bottom));
        left = x0;
        right = x1;
        top = y0;
        bottom = y1;
    }

    /**
     * The frame between a fixed corner and the pointer.
     *
     * <p>With {@code square}, square in the frame's PIXELS, not in fractions: a
     * square in fractions of a 16:9 frame is a wide rectangle on screen. The
     * side is the longer of the two drags, shortened if the frame's edge comes
     * first, so the result stays square rather than being clipped into a
     * rectangle at the border.
     *
     * @param aspect the frame's width divided by its height
     */
    public static Crop spanning(Stroke.Point anchor, Stroke.Point pointer, boolean square, double aspect) {
        if (!square || aspect <= 0) {
            return new Crop(anchor.x(), anchor.y(), pointer.x(), pointer.y());
        }

        // Everything in units of the frame's width: a vertical fraction times
        // height/width, which is 1/aspect.
        double dx = pointer.x() - anchor.x();
        double dy = (pointer.y() - anchor.y()) / aspect;
        double signX = dx < 0 ? -1 : 1;
        double signY = dy < 0 ? -1 : 1;

        double room = Math.min(
                signX > 0 ? 1.0 - anchor.x() : anchor.x(),
                (signY > 0 ? 1.0 - anchor.y() : anchor.y()) / aspect);
        double side = Math.min(Math.max(Math.abs(dx), Math.abs(dy)), room);

        return new Crop(anchor.x(), anchor.y(),
                (float) (anchor.x() + signX * side),
                (float) (anchor.y() + signY * side * aspect));
    }

    /**
     * This crop with one handle dragged to {@code pointer}.
     *
     * <p>Always called on the crop as it was when the drag began, not on the
     * previous step: dragging the left edge past the right one then simply
     * swaps them, instead of the frame collapsing and staying collapsed.
     */
    public Crop resized(Handle handle, Stroke.Point pointer, boolean square, double aspect) {
        return switch (handle) {
            case NW -> spanning(new Stroke.Point(right, bottom), pointer, square, aspect);
            case NE -> spanning(new Stroke.Point(left, bottom), pointer, square, aspect);
            case SE -> spanning(new Stroke.Point(left, top), pointer, square, aspect);
            case SW -> spanning(new Stroke.Point(right, top), pointer, square, aspect);
            case N -> new Crop(left, pointer.y(), right, bottom);
            case S -> new Crop(left, top, right, pointer.y());
            case W -> new Crop(pointer.x(), top, right, bottom);
            case E -> new Crop(left, top, pointer.x(), bottom);
            case MOVE -> this;
        };
    }

    /** Moved as a whole, stopping at the frame's edge with its size unchanged. */
    public Crop moved(double dx, double dy) {
        double x = Math.clamp(left + dx, 0.0, 1.0 - width());
        double y = Math.clamp(top + dy, 0.0, 1.0 - height());

        return new Crop((float) x, (float) y, (float) (x + width()), (float) (y + height()));
    }

    /**
     * Which handle is under the pointer, or null when the pointer is outside.
     *
     * <p>Corners before edges and edges before the inside, so a small frame
     * can still be resized: its handles would otherwise be covered by MOVE.
     *
     * @param reachX how close counts, as a fraction of the frame's width
     * @param reachY the same, of its height
     */
    public Handle handleAt(double x, double y, double reachX, double reachY) {
        boolean nearLeft = Math.abs(x - left) <= reachX;
        boolean nearRight = Math.abs(x - right) <= reachX;
        boolean nearTop = Math.abs(y - top) <= reachY;
        boolean nearBottom = Math.abs(y - bottom) <= reachY;
        boolean withinX = x >= left - reachX && x <= right + reachX;
        boolean withinY = y >= top - reachY && y <= bottom + reachY;

        if (nearTop && nearLeft) {
            return Handle.NW;
        }
        if (nearTop && nearRight) {
            return Handle.NE;
        }
        if (nearBottom && nearRight) {
            return Handle.SE;
        }
        if (nearBottom && nearLeft) {
            return Handle.SW;
        }
        if (nearTop && withinX) {
            return Handle.N;
        }
        if (nearBottom && withinX) {
            return Handle.S;
        }
        if (nearLeft && withinY) {
            return Handle.W;
        }
        if (nearRight && withinY) {
            return Handle.E;
        }
        if (x > left && x < right && y > top && y < bottom) {
            return Handle.MOVE;
        }
        return null;
    }

    public float width() {
        return right - left;
    }

    public float height() {
        return bottom - top;
    }

    /**
     * The rectangle to cut from an image of this size: x, y, width, height.
     *
     * <p>Rounded to the nearest pixel edge on every side, and never empty, so
     * the preview's darkening and the cut agree to within a pixel of the
     * preview at any scale.
     */
    public int[] pixels(int imageWidth, int imageHeight) {
        int x0 = Math.clamp(Math.round(left * imageWidth), 0, imageWidth - 1);
        int y0 = Math.clamp(Math.round(top * imageHeight), 0, imageHeight - 1);
        int x1 = Math.clamp(Math.round(right * imageWidth), x0 + 1, imageWidth);
        int y1 = Math.clamp(Math.round(bottom * imageHeight), y0 + 1, imageHeight);

        return new int[]{x0, y0, x1 - x0, y1 - y0};
    }

    /** Too small to be what the player meant, on an image of this size. */
    public boolean tooSmall(int imageWidth, int imageHeight) {
        int[] rect = pixels(imageWidth, imageHeight);
        return rect[2] < MIN_PIXELS || rect[3] < MIN_PIXELS;
    }

    /** Keeps the whole image, which is the same as not cropping at all. */
    public boolean whole(int imageWidth, int imageHeight) {
        int[] rect = pixels(imageWidth, imageHeight);
        return rect[0] == 0 && rect[1] == 0 && rect[2] == imageWidth && rect[3] == imageHeight;
    }

    private static float clamp(float value) {
        return Math.clamp(value, 0.0F, 1.0F);
    }
}
