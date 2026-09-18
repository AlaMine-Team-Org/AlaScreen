package day.alacraft.alascreen.mc;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * The little plaque that says what was just captured, and which key shares it.
 *
 * <p>A vanilla {@link Toast} rather than a HUD layer of our own, and the reason
 * is not convenience. The two loaders expose different HUD APIs whose <em>timing</em>
 * differs — on NeoForge a layer registered above everything gets no visibility
 * predicate and survives F1; on Fabric the last layer hangs off the subtitle
 * root, which vanilla defers or does not depending on whether a screen is open.
 * Both would call the same shared method, so {@code loader_parity_check.py} would
 * stay green while the plaque behaved differently on the two loaders. A toast has
 * no loader API at all, so that class of divergence cannot happen here.
 *
 * <p>It also inherits, for free, everything a hand-written overlay would have to
 * reproduce: five slots so we never overlap somebody's advancement, the 600 ms
 * slide, and the player's own <em>Notification Time</em> accessibility setting.
 *
 * <p><b>There is no button on it.</b> Not an omission: while the game has no
 * screen open the cursor is {@code GLFW_CURSOR_DISABLED} and has no screen
 * position at all, so mouse input reaches only an open {@code Screen} — a
 * clickable plaque is not a thing that can exist in 26.2. The action is a key
 * press instead, which works precisely while the plaque is up.
 *
 * <p><b>The plaque is not the safety check.</b> A small thumbnail cannot show
 * whether the frame carries F3 coordinates or someone's name in chat, so the key
 * opens the ordinary confirmation screen rather than uploading. It saves the
 * player a reach for the mouse, not a look at the frame.
 */
public final class ShotToast implements Toast {

    /**
     * One plaque at a time.
     *
     * <p>{@link ToastManager#getToast} looks a toast up by this token, so a
     * second capture replaces the first plaque instead of stacking beside it.
     */
    private static final Object TOKEN = new Object();

    /** Vanilla's own toast background, verified in the 26.2 jar. */
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("toast/system");

    /** Vanilla's width, so we line up with every other toast on screen. */
    private static final int WIDTH = Toast.DEFAULT_WIDTH;

    /**
     * The content starts after vanilla's decoration, not at the edge.
     *
     * <p>{@code toast/system.png} is a 160x64 nine-slice whose {@code .mcmeta}
     * declares a left border of 17 and a top border of 30 — and that left strip
     * is not empty padding, it carries the little coloured tab you see on every
     * vanilla toast. Drawing at x=7 puts our picture on top of it. Vanilla's own
     * {@code SystemToast} starts its text at exactly 18, so that is the grid.
     */
    private static final int LEFT = 18;

    private static final int TOP = 7;

    private static final int BOTTOM = 7;

    /** The right edge of the nine-slice is 4px; a little more keeps text off it. */
    private static final int RIGHT = 8;

    /** Vanilla steps toast lines by 12, not by the font's 9. Matching it keeps us in rhythm. */
    private static final int LINE = 12;

    /** Between the title and the row below it. */
    private static final int TITLE_GAP = 4;

    private static final int THUMBNAIL_WIDTH = 48;

    /** So a tall or square screenshot cannot push the plaque out of proportion. */
    private static final int THUMBNAIL_MAX_HEIGHT = 32;

    /** Between the thumbnail and the hint beside it. */
    private static final int THUMBNAIL_GAP = 6;

    /**
     * Caps, because the plaque is a corner notification and not a window.
     *
     * <p>Fourteen languages means a line that is four words in English can be
     * seven somewhere else. Wrapping handles that; these stop the wrapping from
     * growing the plaque without limit when a translation is unusually long or a
     * resource pack ships a wide font. What does not fit is dropped rather than
     * drawn over the edge.
     */
    private static final int MAX_TITLE_LINES = 2;

    private static final int MAX_HINT_LINES = 3;

    /** Vanilla's own display time for a system toast, before the player's multiplier. */
    private static final long SHOW_FOR_MS = 5000L;

    /**
     * Colours are ARGB here, and the alpha is not optional.
     *
     * <p>Written as {@code 0xFFFFFF} — the shape every other colour constant in
     * this mod has — the text is drawn at alpha zero, which is to say invisible:
     * the plaque renders its background and its picture and simply has no words
     * on it. Nothing fails, nothing is logged. Vanilla's own toast passes
     * {@code -1} and {@code -256}, both with the top byte set, which is what
     * these are.
     */
    private static final int TITLE = 0xFFFFFFFF;
    private static final int HINT = 0xFFA0A0A0;
    private static final int FRAME = 0xFF3A3A3A;

    private final Identifier textureId;

    private List<FormattedCharSequence> titleLines;
    private List<FormattedCharSequence> hintLines;

    private int thumbnailWidth;
    private int thumbnailHeight;
    private int height;

    /** Flipped by {@link #dismiss()} so the next capture is not photographed with the plaque in it. */
    private boolean dismissed;

    private Visibility wanted = Visibility.SHOW;

    private boolean finished;

    /**
     * The manager's clock reading when this plaque last started showing.
     *
     * <p>Not a wall clock: {@code update} is handed how long the toast has been
     * fully visible, and the countdown has to restart from there when the plaque
     * is reused for a second screenshot. {@code -1} means "starts on the next
     * update", which is also what {@link #reset} puts back.
     */
    private long showingSince = -1;

    public ShotToast(Identifier textureId, Font font, int shotWidth, int shotHeight,
                     Component title, Component hint) {
        this.textureId = textureId;

        layOut(font, shotWidth, shotHeight, title, hint);
    }

    /**
     * Show a new screenshot on the plaque that is already up.
     *
     * <p>Vanilla's own answer to the same problem ({@code SystemToast.addOrUpdate}),
     * and here it is not a nicety: adding a second toast under the same token
     * would leave two plaques sharing one texture id, and the moment the older
     * one's picture was freed the survivor would be drawing an id nobody has
     * registered — which the texture manager answers with a placeholder and a
     * permanent bad entry rather than with nothing.
     */
    public void reset(Font font, int shotWidth, int shotHeight, Component title, Component hint) {
        layOut(font, shotWidth, shotHeight, title, hint);

        dismissed = false;
        finished = false;
        showingSince = -1;
        wanted = Visibility.SHOW;
    }

    /**
     * Measure everything once, and let the height follow the content.
     *
     * <p>Done here rather than while drawing because {@link #height()} is asked
     * before the first frame — the manager needs it to reserve slots. A fixed
     * height would mean either empty space under short text or clipped text under
     * long, and with fourteen languages both happen on the same plaque.
     */
    private void layOut(Font font, int shotWidth, int shotHeight, Component title, Component hint) {
        int titleWidth = WIDTH - LEFT - RIGHT;
        int hintWidth = WIDTH - LEFT - THUMBNAIL_WIDTH - THUMBNAIL_GAP - RIGHT;

        titleLines = clip(font.split(title, titleWidth), MAX_TITLE_LINES);
        hintLines = clip(font.split(hint, hintWidth), MAX_HINT_LINES);

        if (shotWidth > 0 && shotHeight > 0) {
            double scale = Math.min(THUMBNAIL_WIDTH / (double) shotWidth,
                    THUMBNAIL_MAX_HEIGHT / (double) shotHeight);

            thumbnailWidth = Math.max(1, (int) Math.round(shotWidth * scale));
            thumbnailHeight = Math.max(1, (int) Math.round(shotHeight * scale));
        } else {
            thumbnailWidth = 0;
            thumbnailHeight = 0;
        }

        int rowHeight = Math.max(thumbnailHeight, hintLines.size() * LINE);

        height = TOP + titleLines.size() * LINE + TITLE_GAP + rowHeight + BOTTOM;
    }

    private static List<FormattedCharSequence> clip(List<FormattedCharSequence> lines, int max) {
        return lines.size() <= max ? lines : lines.subList(0, max);
    }

    @Override
    public Object getToken() {
        return TOKEN;
    }

    /**
     * The token, so the owner can ask the manager whether this plaque still exists.
     *
     * <p>{@code ToastManager.getToast} searches both the visible toasts and the
     * queued ones, so a plaque waiting for a free slot is not mistaken for a
     * discarded one — which matters, because mistaking it would free the picture
     * out from under a toast that is about to be drawn.
     */
    public static Object token() {
        return TOKEN;
    }

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public Visibility getWantedVisibility() {
        return wanted;
    }

    @Override
    public void update(ToastManager manager, long fullyVisibleForMs) {
        if (showingSince < 0) {
            showingSince = fullyVisibleForMs;
        }

        // The multiplier is the player's Notification Time setting. Inheriting it
        // beats picking our own number: whatever we chose would be wrong for the
        // half of players who have already told the game how long they want to
        // read a plaque.
        double showFor = SHOW_FOR_MS * manager.getNotificationDisplayTimeMultiplier();

        wanted = !dismissed && fullyVisibleForMs - showingSince < showFor
                ? Visibility.SHOW
                : Visibility.HIDE;
    }

    /** Take the plaque down now — before the next capture, so it cannot land in the frame. */
    public void dismiss() {
        dismissed = true;
        wanted = Visibility.HIDE;
    }

    /** True while the plaque is on screen, which is exactly when the key means "share this". */
    public boolean isShowing() {
        return !dismissed && !finished && wanted == Visibility.SHOW;
    }

    public boolean hasFinished() {
        return finished;
    }

    @Override
    public void onFinishedRendering() {
        // Only a flag. Freeing the texture here would be both too early and not
        // enough: too early because the frame's BlitRenderState still holds the
        // texture view, and not enough because ToastManager.clear() — which runs
        // on every disconnect — throws toasts away without calling this at all.
        finished = true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long fullyVisibleForMs) {
        // Local coordinates: the manager has already translated to our slot and
        // applied the slide, so (0,0) is the plaque's own top-left corner.
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, WIDTH, height);

        int y = TOP;

        for (FormattedCharSequence line : titleLines) {
            graphics.text(font, line, LEFT, y, TITLE, false);
            y += LINE;
        }

        y += TITLE_GAP;

        drawThumbnail(graphics, y);

        int hintY = y;

        for (FormattedCharSequence line : hintLines) {
            graphics.text(font, line, LEFT + THUMBNAIL_WIDTH + THUMBNAIL_GAP, hintY, HINT, false);
            hintY += LINE;
        }
    }

    /**
     * Draw the captured frame, scaled to fit and never stretched.
     *
     * <p>Same rule as the confirmation screen: this is a picture of what the
     * player just took, and a squashed one would misrepresent it.
     */
    private void drawThumbnail(GuiGraphicsExtractor graphics, int top) {
        if (thumbnailWidth <= 0 || thumbnailHeight <= 0) {
            return;
        }

        int x0 = LEFT + (THUMBNAIL_WIDTH - thumbnailWidth) / 2;

        // A one-pixel frame, so a night shot does not read as an empty slot.
        graphics.fill(x0 - 1, top - 1, x0 + thumbnailWidth + 1, top + thumbnailHeight + 1, FRAME);

        // CORNERS, not a size — the last two ints are x1/y1. Passing width and
        // height compiles and silently draws the wrong thing (rule 10).
        graphics.blit(textureId, x0, top, x0 + thumbnailWidth, top + thumbnailHeight, 0.0F, 1.0F, 0.0F, 1.0F);
    }
}
