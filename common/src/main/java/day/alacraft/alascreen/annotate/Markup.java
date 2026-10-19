package day.alacraft.alascreen.annotate;

import java.util.List;
import java.util.Optional;

/**
 * Everything the player did to the frame on the confirmation screen.
 *
 * <p>The marks are painted onto the whole frame first and the crop is cut
 * second. A mark's width is a fraction of the WHOLE frame's shorter side, as
 * it was on the preview; painting onto the cut-out instead would make every
 * line thicker than the one the player drew.
 */
public record Markup(List<Stroke> strokes, Optional<Crop> crop) {

    public static final Markup NONE = new Markup(List.of(), Optional.empty());

    public Markup {
        strokes = List.copyOf(strokes);
    }

    public boolean isEmpty() {
        return strokes.isEmpty() && crop.isEmpty();
    }
}
