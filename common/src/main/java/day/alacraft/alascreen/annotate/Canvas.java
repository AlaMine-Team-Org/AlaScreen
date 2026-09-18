package day.alacraft.alascreen.annotate;

/**
 * Pixels the rasteriser paints onto, ARGB.
 *
 * <p>An interface rather than an {@code int[]} so a full 4K frame is painted in
 * place: copying it out to an array and back would be a second 33 MB for every
 * shot that carries a single arrow.
 */
public interface Canvas {

    int width();

    int height();

    void set(int x, int y, int argb);
}
