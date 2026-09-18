package day.alacraft.alascreen.annotate;

import java.util.List;

/**
 * One mark on the picture.
 *
 * <p>Points are fractions of the image (0..1), not pixels, and the width is a
 * fraction of its shorter side. The same stroke is painted twice — onto the
 * small preview while the player draws, and onto the full frame that is
 * uploaded — and both must show the same thing, so nothing here may know which
 * of the two it is on.
 *
 * @param points two for ARROW, FRAME and REDACT (start and end), any number for PEN
 */
public record Stroke(Tool tool, int argb, float width, List<Point> points) {

    public Stroke {
        points = List.copyOf(points);
    }

    public record Point(float x, float y) {

        /** Clamped, so a drag that leaves the picture still ends on its edge. */
        public static Point of(double x, double y) {
            return new Point((float) Math.clamp(x, 0.0, 1.0), (float) Math.clamp(y, 0.0, 1.0));
        }
    }
}
