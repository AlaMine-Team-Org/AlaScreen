package day.alacraft.alascreen.annotate;

import java.util.List;

/**
 * Paints strokes onto a canvas.
 *
 * <p>Plain Java on purpose: this is what decides the pixels that leave the
 * machine, so it has to be testable without a game, and the preview and the
 * upload have to come from the same code — a preview drawn by the GUI and an
 * upload drawn by something else would sooner or later disagree about where an
 * arrow ends or whether the cover-up covers.
 *
 * <p>Colours are written, not blended: every colour the editor offers is opaque.
 */
public final class Rasterizer {

    private Rasterizer() {
    }

    public static void paint(Canvas canvas, List<Stroke> strokes) {
        for (Stroke stroke : strokes) {
            paint(canvas, stroke);
        }
    }

    public static void paint(Canvas canvas, Stroke stroke) {
        List<Stroke.Point> points = stroke.points();

        if (points.isEmpty() || canvas.width() <= 0 || canvas.height() <= 0) {
            return;
        }

        // Not rounded: 1.8 px on the preview must stay a tenth of 18 px on the frame.
        double thickness = Math.max(1.0, stroke.width() * Math.min(canvas.width(), canvas.height()));
        double radius = thickness / 2.0;

        double[] first = pixel(canvas, points.getFirst());
        double[] last = pixel(canvas, points.getLast());

        switch (stroke.tool()) {
            case PEN -> {
                if (points.size() == 1) {
                    segment(canvas, first, first, radius, stroke.argb());
                }
                for (int i = 1; i < points.size(); i++) {
                    segment(canvas, pixel(canvas, points.get(i - 1)), pixel(canvas, points.get(i)), radius, stroke.argb());
                }
            }
            case ARROW -> arrow(canvas, first, last, thickness, stroke.argb());
            case FRAME -> frame(canvas, first, last, (int) Math.max(1, Math.round(thickness)), stroke.argb());
            case REDACT -> rect(canvas,
                    (int) Math.floor(Math.min(first[0], last[0])), (int) Math.floor(Math.min(first[1], last[1])),
                    (int) Math.ceil(Math.max(first[0], last[0])), (int) Math.ceil(Math.max(first[1], last[1])),
                    stroke.argb());
        }
    }

    private static double[] pixel(Canvas canvas, Stroke.Point point) {
        return new double[]{point.x() * canvas.width(), point.y() * canvas.height()};
    }

    /**
     * A shaft that stops at the base of the head, so the tip stays sharp.
     *
     * <p>The head grows with the line: the same arrow at the thinnest setting on
     * a 4K frame would otherwise be a hair with a speck on the end.
     */
    private static void arrow(Canvas canvas, double[] from, double[] to, double thickness, int argb) {
        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        double length = Math.hypot(dx, dy);

        if (length < 1.0) {
            segment(canvas, to, to, thickness / 2.0, argb);
            return;
        }

        double ux = dx / length;
        double uy = dy / length;
        double head = Math.min(length, Math.max(thickness * 4.0, 6.0));
        double half = head * 0.6;

        double[] base = {to[0] - ux * head, to[1] - uy * head};
        segment(canvas, from, base, thickness / 2.0, argb);

        triangle(canvas,
                to,
                new double[]{base[0] - uy * half, base[1] + ux * half},
                new double[]{base[0] + uy * half, base[1] - ux * half},
                argb);
    }

    private static void frame(Canvas canvas, double[] a, double[] b, int thickness, int argb) {
        int x0 = (int) Math.floor(Math.min(a[0], b[0]));
        int y0 = (int) Math.floor(Math.min(a[1], b[1]));
        int x1 = (int) Math.ceil(Math.max(a[0], b[0]));
        int y1 = (int) Math.ceil(Math.max(a[1], b[1]));

        rect(canvas, x0, y0, x1, y0 + thickness, argb);
        rect(canvas, x0, y1 - thickness, x1, y1, argb);
        rect(canvas, x0, y0, x0 + thickness, y1, argb);
        rect(canvas, x1 - thickness, y0, x1, y1, argb);
    }

    /** Every pixel whose centre is within {@code radius} of the segment: round caps, no gaps at joints. */
    private static void segment(Canvas canvas, double[] a, double[] b, double radius, int argb) {
        int x0 = Math.max(0, (int) Math.floor(Math.min(a[0], b[0]) - radius));
        int y0 = Math.max(0, (int) Math.floor(Math.min(a[1], b[1]) - radius));
        int x1 = Math.min(canvas.width() - 1, (int) Math.ceil(Math.max(a[0], b[0]) + radius));
        int y1 = Math.min(canvas.height() - 1, (int) Math.ceil(Math.max(a[1], b[1]) + radius));

        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double lengthSquared = dx * dx + dy * dy;
        double limit = radius * radius;

        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double px = x + 0.5 - a[0];
                double py = y + 0.5 - a[1];
                double t = lengthSquared == 0 ? 0 : Math.clamp((px * dx + py * dy) / lengthSquared, 0.0, 1.0);
                double ex = px - t * dx;
                double ey = py - t * dy;

                if (ex * ex + ey * ey <= limit) {
                    canvas.set(x, y, argb);
                }
            }
        }
    }

    private static void triangle(Canvas canvas, double[] a, double[] b, double[] c, int argb) {
        int x0 = Math.max(0, (int) Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
        int y0 = Math.max(0, (int) Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
        int x1 = Math.min(canvas.width() - 1, (int) Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int y1 = Math.min(canvas.height() - 1, (int) Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));

        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double px = x + 0.5;
                double py = y + 0.5;
                double e0 = edge(a, b, px, py);
                double e1 = edge(b, c, px, py);
                double e2 = edge(c, a, px, py);

                // Either winding: the corners come from a direction, not a fixed order.
                if ((e0 >= 0 && e1 >= 0 && e2 >= 0) || (e0 <= 0 && e1 <= 0 && e2 <= 0)) {
                    canvas.set(x, y, argb);
                }
            }
        }
    }

    private static double edge(double[] a, double[] b, double x, double y) {
        return (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
    }

    /** Half-open, clipped to the canvas. */
    private static void rect(Canvas canvas, int x0, int y0, int x1, int y1, int argb) {
        int left = Math.max(0, x0);
        int top = Math.max(0, y0);
        int right = Math.min(canvas.width(), x1);
        int bottom = Math.min(canvas.height(), y1);

        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                canvas.set(x, y, argb);
            }
        }
    }
}
