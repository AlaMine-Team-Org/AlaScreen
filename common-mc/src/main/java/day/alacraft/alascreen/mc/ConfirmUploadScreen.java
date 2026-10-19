package day.alacraft.alascreen.mc;

import day.alacraft.alascreen.AlaScreen;
import day.alacraft.alascreen.annotate.Crop;
import day.alacraft.alascreen.annotate.Markup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Shows the frame and asks before any of it leaves the machine.
 *
 * <p>This screen is the mod's one real safety feature, and it is here because
 * there is no other honest option. Ala Logger can strip an access token out of a
 * log with a regular expression; nothing comparable exists for pixels. A
 * screenshot can carry chat, an F3 overlay with coordinates, a server address,
 * someone's real name in a nametag — and the only reliable detector for all of
 * that is the person who took the shot, looking at it.
 *
 * <p>So the default is to look. It can be turned off, because someone taking
 * fifty shots an evening will otherwise stop using the mod, but turning it off
 * is a choice the player makes rather than one we make for them.
 */
public final class ConfirmUploadScreen extends Screen {

    /** Height reserved for the title and the warning above the preview. */
    private static final int HEADER = 56;

    private static final int MARGIN = 24;

    private static final int BUTTON_HEIGHT = 20;

    /** Space either side of a button's label, matching vanilla's own buttons. */
    private static final int BUTTON_PADDING = 12;

    /** So a short label such as "Upload" does not become a square. */
    private static final int MIN_BUTTON_WIDTH = 80;

    // ARGB, all eight digits. Written as 0xFFFFFF these were white at zero
    // opacity, and 26.2 skips text whose alpha is zero: the title, the warning
    // and the details line shipped invisible in 0.1.0 to 0.1.2, on the one
    // screen that exists to be read. ArgbColorLintTest now fails the build on it.
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFFA0A0A0;
    private static final int WARNING = 0xFFFFD24A;

    private final CapturedShot shot;
    private final BiConsumer<CapturedShot, Markup> onUpload;
    private final BiConsumer<CapturedShot, Markup> onSaveOnly;
    private final Runnable onCancel;
    private final Consumer<Boolean> onRememberChanged;

    private final AnnotationEditor editor;

    private final int sourceWidth;
    private final int sourceHeight;

    private final Identifier textureId;
    private DynamicTexture texture;
    private Checkbox remember;

    /**
     * Top of the button block, so the checkbox and the details line sit above
     * whichever layout was chosen instead of guessing at a fixed offset.
     */
    private int buttonRowTop;

    /** Set once the decision is made, so {@link #removed()} knows not to free the bytes' frame twice. */
    private boolean decided;

    /**
     * @param sourceWidth  the frame a crop is cut from, which is larger than {@code shot}
     *                     when the upload had to be scaled down; equal to it otherwise
     */
    public ConfirmUploadScreen(CapturedShot shot,
                               int sourceWidth,
                               int sourceHeight,
                               BiConsumer<CapturedShot, Markup> onUpload,
                               BiConsumer<CapturedShot, Markup> onSaveOnly,
                               Runnable onCancel,
                               Consumer<Boolean> onRememberChanged) {
        super(Component.translatable("alascreen.confirm.title"));

        this.shot = shot;
        this.onUpload = onUpload;
        this.onSaveOnly = onSaveOnly;
        this.onCancel = onCancel;
        this.onRememberChanged = onRememberChanged;
        this.editor = new AnnotationEditor(Minecraft.getInstance(), sourceWidth, sourceHeight);
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;

        // Unique per capture: two screens in one session must not share a
        // texture id, or the second would show the first one's picture.
        this.textureId = Identifier.fromNamespaceAndPath(
                AlaScreen.MOD_ID, "preview/" + Long.toHexString(System.nanoTime()));
    }

    @Override
    protected void init() {
        if (texture == null) {
            // The texture takes ownership of the frame and closes it with itself.
            texture = new DynamicTexture(() -> AlaScreen.MOD_NAME + " preview", shot.image());
            minecraft.getTextureManager().register(textureId, texture);
        }

        layOutButtons();

        editor.init(width, HEADER, font, this::addRenderableWidget);

        remember = addRenderableWidget(Checkbox.builder(
                        Component.translatable("alascreen.confirm.remember"), font)
                .pos((width - 150) / 2, buttonRowTop - 26)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("alascreen.confirm.remember.tooltip")))
                .build());
    }

    /**
     * Place the three buttons, each as wide as its own label needs.
     *
     * <p>A fixed width is the obvious way to write this and it is wrong for a
     * translated mod: "Upload" is four characters and "Save without uploading"
     * is twenty-two, and in German it is longer still. The first version used
     * 110 pixels for all three and cut the middle label to "Save without
     * uploadin" — in English, the shortest language on the list.
     *
     * <p>So each button is measured, and when the row cannot fit the window they
     * stack instead of shrinking: a full-width column always fits, whatever the
     * locale does to the text.
     */
    private void layOutButtons() {
        List<Button> buttons = new ArrayList<>();

        buttons.add(Button.builder(Component.translatable("alascreen.confirm.upload"),
                button -> confirm()).build());

        // Only offered when there is nothing on disk yet. Reached from the F2
        // message — or from our own capture with saveLocalCopy on — the file has
        // already been written, so "save without uploading" would promise an
        // action that happened before the screen opened. A button that does
        // nothing is worse than one that is not there.
        if (shot.savedTo().isEmpty()) {
            buttons.add(Button.builder(Component.translatable("alascreen.confirm.save_only"),
                    button -> saveOnly()).build());
        }

        buttons.add(Button.builder(Component.translatable("alascreen.confirm.cancel"),
                button -> onClose()).build());

        int gap = 6;
        int available = width - MARGIN * 2;

        int row = -gap;
        for (Button button : buttons) {
            row += widthFor(button.getMessage()) + gap;
        }

        if (row <= available) {
            int x = (width - row) / 2;
            int y = height - 34;

            for (Button button : buttons) {
                int buttonWidth = widthFor(button.getMessage());
                button.setPosition(x, y);
                button.setWidth(buttonWidth);
                addRenderableWidget(button);
                x += buttonWidth + gap;
            }

            buttonRowTop = y;
            return;
        }

        // Stacked: one column, every button the same generous width. A row that
        // does not fit is a translation problem, not a rare one — the same three
        // words are half again as long in German.
        int stackWidth = Math.min(available, 240);
        int x = (width - stackWidth) / 2;
        int y = height - 34 - (BUTTON_HEIGHT + 4) * (buttons.size() - 1);

        buttonRowTop = y;

        for (Button button : buttons) {
            button.setPosition(x, y);
            button.setWidth(stackWidth);
            addRenderableWidget(button);
            y += BUTTON_HEIGHT + 4;
        }
    }

    /**
     * How wide a button holding this label has to be.
     *
     * <p>Vanilla's own buttons leave room either side of the text; without that
     * padding the label touches the border and reads as clipped even when it is
     * not. The floor keeps a one-word label from becoming a tiny square next to
     * its neighbours.
     */
    private int widthFor(Component label) {
        return Math.max(MIN_BUTTON_WIDTH, font.width(label) + BUTTON_PADDING * 2);
    }

    /**
     * Keep the file, share nothing.
     *
     * <p>Its own path rather than an alias for Cancel, because with
     * {@code saveLocalCopy} turned off the capture exists only in memory: the
     * button promised a file and closing the screen would have quietly produced
     * none.
     */
    private void saveOnly() {
        decided = true;
        // Before the screen closes: the marks are burnt into a copy of the
        // frame, and the frame is freed with the preview texture in removed().
        onSaveOnly.accept(shot, editor.markup());
        minecraft.gui.setScreen(null);
    }

    private void confirm() {
        decided = true;

        if (remember != null && remember.selected()) {
            onRememberChanged.accept(Boolean.TRUE);
        }

        onUpload.accept(shot, editor.markup());

        // Closed directly rather than through onClose(): this screen's onClose is
        // the cancel path and would report a cancellation we just overrode.
        // 26.2 moved the setter onto Gui — Minecraft.setScreen no longer exists.
        minecraft.gui.setScreen(null);
    }

    @Override
    public void onClose() {
        if (!decided) {
            decided = true;
            onCancel.run();
        }

        minecraft.gui.setScreen(null);
    }

    @Override
    public void removed() {
        // Releasing the texture closes the frame it owns. Nothing else may touch
        // the image after this point — the encoded bytes are what the upload
        // uses, and they are a plain array that outlives all of this.
        if (texture != null) {
            minecraft.getTextureManager().release(textureId);
            texture = null;
        }

        editor.release();

        super.removed();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }

        int[] preview = previewRect();
        return preview != null && editor.mouseClicked(event.x(), event.y(), event.button(), preview);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        int[] preview = previewRect();

        if (preview != null && editor.mouseDragged(event.x(), event.y(), event.hasShiftDown(), preview)) {
            return true;
        }

        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (editor.mouseReleased()) {
            return true;
        }

        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (InputCompat.isUndo(event) && editor.undoKey()) {
            return true;
        }

        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Before the widgets, so the panel sits under its own buttons.
        editor.extractPanel(graphics);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        Font f = this.font;

        graphics.centeredText(f, title, width / 2, 18, TEXT);

        // Wrapped rather than one long line: the sentence is the whole reason
        // this screen exists, and it must stay readable on a narrow window.
        // Centred line by line, under the centred title.
        int line = 34;
        for (FormattedCharSequence part : f.split(Component.translatable("alascreen.confirm.warning"), width - MARGIN * 2)) {
            graphics.centeredText(f, part, width / 2, line, WARNING);
            line += f.lineHeight;
        }

        drawPreview(graphics);

        graphics.centeredText(f, details(), width / 2, buttonRowTop - 42, MUTED);

        editor.extractDecorations(graphics);
    }

    /**
     * Where the capture is drawn: x, y, width, height, or null when there is no room.
     *
     * <p>Scaled to fit and never stretched. Aspect ratio is preserved
     * deliberately: this is the image the player is being asked to judge, and a
     * squashed preview would misrepresent what is actually in the frame. One
     * method for drawing and for the mouse, so a mark lands where it was drawn.
     */
    private int[] previewRect() {
        int left = Math.max(MARGIN, AnnotationEditor.SIDE_RESERVE);
        int right = width - left;
        int boxWidth = right - left;
        int boxHeight = Math.max(0, buttonRowTop - 48 - HEADER);

        if (boxWidth <= 0 || boxHeight <= 0 || shot.width() <= 0 || shot.height() <= 0) {
            return null;
        }

        double scale = Math.min(boxWidth / (double) shot.width(), boxHeight / (double) shot.height());
        // Never upscale: a preview larger than the capture would look blurry and
        // would suggest a resolution the file does not have.
        scale = Math.min(scale, 1.0);

        int drawWidth = Math.max(1, (int) Math.round(shot.width() * scale));
        int drawHeight = Math.max(1, (int) Math.round(shot.height() * scale));

        return new int[]{left + (boxWidth - drawWidth) / 2, HEADER + (boxHeight - drawHeight) / 2, drawWidth, drawHeight};
    }

    private void drawPreview(GuiGraphicsExtractor graphics) {
        int[] preview = previewRect();

        if (preview == null) {
            return;
        }

        int x0 = preview[0];
        int y0 = preview[1];
        int drawWidth = preview[2];
        int drawHeight = preview[3];

        // A one-pixel frame so a dark screenshot does not blend into the
        // background and read as "nothing was captured".
        graphics.fill(x0 - 1, y0 - 1, x0 + drawWidth + 1, y0 + drawHeight + 1, 0xFF3A3A3A);

        // 26.2 takes CORNERS here, not a size: the last two ints are x1/y1.
        // Passing width and height compiles and silently draws the wrong thing.
        graphics.blit(textureId, x0, y0, x0 + drawWidth, y0 + drawHeight, 0.0F, 1.0F, 0.0F, 1.0F);

        editor.extractOverlay(graphics, x0, y0, drawWidth, drawHeight);
        editor.extractCrop(graphics, x0, y0, drawWidth, drawHeight);
    }

    /**
     * Size and weight of what is about to leave, or, with a crop, the size it is cut to.
     *
     * <p>The cut is measured on the full frame, which is what it will be taken
     * from. The byte count is left out there: it is only known after encoding,
     * and a number that is about to be wrong is worse than none.
     */
    private Component details() {
        Crop crop = editor.visibleCrop();

        if (crop == null) {
            return Component.translatable("alascreen.confirm.details",
                    shot.width(), shot.height(), ChatFormat.size(shot.bytes()));
        }

        int[] cut = crop.pixels(sourceWidth, sourceHeight);
        return Component.translatable("alascreen.confirm.details_cropped", cut[2], cut[3]);
    }

    @Override
    public boolean isPauseScreen() {
        // Pausing a singleplayer world while the player decides is the polite
        // behaviour, and it also freezes the scene the shot was taken from.
        return true;
    }
}
