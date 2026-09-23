package day.alacraft.alascreen.mc;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import day.alacraft.alascreen.AlaScreen;
import day.alacraft.alascreen.annotate.Rasterizer;
import day.alacraft.alascreen.annotate.Stroke;
import day.alacraft.alascreen.annotate.Tool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The mark-up panel on the confirmation screen: arrows, frames, a pen and a
 * cover-up, drawn over the preview and burnt into what is uploaded.
 *
 * <p>Closed by default. The confirmation screen exists to be read, and a
 * player who never marks anything up should see exactly the screen they saw
 * before this existed, plus one button in the corner.
 *
 * <p>The marks are shown through the same {@link Rasterizer} that later paints
 * the full frame, into a texture the size of the preview in real pixels. A
 * preview drawn with GUI fills would be a second implementation of every tool,
 * and the one place they disagreed would be the one the player trusted.
 */
final class AnnotationEditor {

    static final int DOCK_WIDTH = 52;

    /**
     * Kept free on BOTH sides of the preview, open or not.
     *
     * <p>Reserving it only while the panel is open made the picture jump
     * sideways on every toggle, which reads as the screen breaking.
     */
    static final int SIDE_RESERVE = DOCK_WIDTH + 16;

    private static final int PAD = 5;
    private static final int BUTTON = 20;
    private static final int GAP = 2;
    private static final int SECTION = 5;
    private static final int SWATCH = 12;
    private static final int SWATCH_STEP = 15;

    /** Fractions of the picture's shorter side: thin, medium, thick. */
    private static final float[] WIDTHS = {0.004F, 0.008F, 0.014F};

    /** Chat colours, so the palette reads as Minecraft's own: red, yellow, green, aqua, white, black. */
    private static final int[] PALETTE = {0xFFFF5555, 0xFFFFFF55, 0xFF55FF55, 0xFF55FFFF, 0xFFFFFFFF, 0xFF000000};

    /** Fixed, whatever colour is picked: a cover-up is not a highlight, and the palette's white would not look like one. */
    private static final int COVER = 0xFF1E1E1E;

    private static final int PANEL = 0xFFC6C6C6;
    private static final int PANEL_LIGHT = 0xFFFFFFFF;
    private static final int PANEL_SHADOW = 0xFF555555;
    private static final int OUTLINE = 0xFF000000;
    private static final int SLOT = 0xFF8B8B8B;
    private static final int ICON = 0xFFFFFFFF;
    private static final int ICON_OFF = 0xFFA0A0A0;
    private static final int SELECTED = 0xFFFFFFFF;

    private static final Map<Tool, String[]> ICONS = Map.of(
            Tool.ARROW, new String[]{
                    "....######", "......####", ".....#####", "....###.##", "...###...#",
                    "..###.....", ".###......", "###.......", "##........", "#........."},
            Tool.FRAME, new String[]{
                    "##########", "##########", "##......##", "##......##", "##......##",
                    "##......##", "##......##", "##......##", "##########", "##########"},
            Tool.PEN, new String[]{
                    "......###.", ".....#####", "....#####.", "...#####..", "..#####...",
                    ".#####....", ".####.....", "#.##......", "##........", "#........."},
            Tool.REDACT, new String[]{
                    "##########", "#.#.#.#.##", "##.#.#.#.#", "#.#.#.#.##", "##.#.#.#.#",
                    "#.#.#.#.##", "##.#.#.#.#", "#.#.#.#.##", "##.#.#.#.#", "##########"});

    private static final String[] UNDO_ICON = {
            "..#.......", ".##.......", "#######...", ".##....#..", "..#.....#.",
            ".........#", ".........#", "........#.", "......##..", "..####...."};

    private static final String[] CLEAR_ICON = {
            "##......##", "###....###", ".###..###.", "..######..", "...####...",
            "...####...", "..######..", ".###..###.", "###....###", "##......##"};

    private final Minecraft minecraft;
    private final Identifier overlayId;

    private final List<Stroke> strokes = new ArrayList<>();
    private List<Stroke.Point> drawing;
    private Tool tool = Tool.ARROW;
    private int colour = PALETTE[0];
    private int widthIndex = 1;
    private boolean open;

    private Button toggle;
    private final Map<Tool, Button> toolButtons = new EnumMap<>(Tool.class);
    private Button undo;
    private Button clear;

    private int screenWidth;
    private int dockX;
    private int dockY;
    private int dockHeight;
    private int swatchY;
    private int thicknessY;

    private DynamicTexture overlay;
    private int overlayWidth;
    private int overlayHeight;
    private boolean dirty = true;

    AnnotationEditor(Minecraft minecraft) {
        this.minecraft = minecraft;
        this.overlayId = Identifier.fromNamespaceAndPath(
                AlaScreen.MOD_ID, "marks/" + Long.toHexString(System.nanoTime()));
    }

    /** Build the widgets. Called from every {@code init}, so a resize lays the panel out again. */
    void init(int screenWidth, int top, Font font, Consumer<Button> add) {
        this.screenWidth = screenWidth;
        dockX = screenWidth - 8 - DOCK_WIDTH;
        dockY = top;

        int left = dockX + PAD;
        int right = left + BUTTON + GAP;
        int y = dockY + PAD;

        toolButtons.clear();
        add.accept(toolButton(Tool.ARROW, "alascreen.annotate.arrow", left, y));
        add.accept(toolButton(Tool.FRAME, "alascreen.annotate.frame", right, y));
        y += BUTTON + GAP;
        add.accept(toolButton(Tool.PEN, "alascreen.annotate.pen", left, y));
        add.accept(toolButton(Tool.REDACT, "alascreen.annotate.redact", right, y));
        y += BUTTON + SECTION;

        undo = iconButton("alascreen.annotate.undo", left, y, button -> undoLast());
        clear = iconButton("alascreen.annotate.clear", right, y, button -> clearAll());
        add.accept(undo);
        add.accept(clear);
        y += BUTTON + SECTION;

        swatchY = y;
        y += SWATCH * 2 + GAP + SECTION;
        thicknessY = y;
        y += SWATCH + PAD;
        dockHeight = y - dockY;

        toggle = Button.builder(toggleLabel(), button -> toggle(font)).build();
        placeToggle(font);
        add.accept(toggle);

        sync();
    }

    List<Stroke> strokes() {
        return List.copyOf(strokes);
    }

    private Button toolButton(Tool target, String tooltipKey, int x, int y) {
        Button button = Button.builder(Component.empty(), pressed -> tool = target)
                .bounds(x, y, BUTTON, BUTTON)
                .tooltip(Tooltip.create(Component.translatable(tooltipKey)))
                .build();
        toolButtons.put(target, button);
        return button;
    }

    private static Button iconButton(String tooltipKey, int x, int y, Button.OnPress action) {
        return Button.builder(Component.empty(), action)
                .bounds(x, y, BUTTON, BUTTON)
                .tooltip(Tooltip.create(Component.translatable(tooltipKey)))
                .build();
    }

    private Component toggleLabel() {
        return Component.translatable(open ? "alascreen.annotate.close" : "alascreen.annotate.open");
    }

    /** Measured, not fixed: "Mark up" and its German counterpart are not the same width. */
    private void placeToggle(Font font) {
        int width = font.width(toggle.getMessage()) + 16;
        toggle.setWidth(width);
        toggle.setPosition(screenWidth - 4 - width, 4);
    }

    private void toggle(Font font) {
        open = !open;
        drawing = null;
        toggle.setMessage(toggleLabel());
        placeToggle(font);
        sync();
    }

    private void undoLast() {
        if (!strokes.isEmpty()) {
            strokes.removeLast();
            changed();
        }
    }

    private void clearAll() {
        strokes.clear();
        changed();
    }

    /** Ctrl+Z. Works with the panel folded too: the marks are still on the picture. */
    boolean undoKey() {
        if (strokes.isEmpty()) {
            return false;
        }
        undoLast();
        return true;
    }

    private void changed() {
        dirty = true;
        sync();
    }

    private void sync() {
        for (Button button : toolButtons.values()) {
            button.visible = open;
        }
        undo.visible = open;
        clear.visible = open;
        undo.active = !strokes.isEmpty();
        clear.active = !strokes.isEmpty();
    }

    // ---- input ----

    /** @param preview x, y, width, height of the picture on screen */
    boolean mouseClicked(double x, double y, int button, int[] preview) {
        if (!open) {
            return false;
        }

        for (int i = 0; i < PALETTE.length; i++) {
            if (inside(x, y, swatchX(i), swatchRowY(i), SWATCH, SWATCH)) {
                colour = PALETTE[i];
                return true;
            }
        }

        for (int i = 0; i < WIDTHS.length; i++) {
            if (inside(x, y, swatchX(i), thicknessY, SWATCH, SWATCH)) {
                widthIndex = i;
                return true;
            }
        }

        if (button != InputConstants.MOUSE_BUTTON_LEFT || !inside(x, y, preview[0], preview[1], preview[2], preview[3])) {
            return false;
        }

        drawing = new ArrayList<>();
        drawing.add(point(x, y, preview));
        dirty = true;
        return true;
    }

    boolean mouseDragged(double x, double y, int[] preview) {
        if (drawing == null) {
            return false;
        }

        Stroke.Point point = point(x, y, preview);

        if (tool == Tool.PEN) {
            if (!point.equals(drawing.getLast())) {
                drawing.add(point);
            }
        } else if (drawing.size() == 1) {
            drawing.add(point);
        } else {
            drawing.set(1, point);
        }

        dirty = true;
        return true;
    }

    boolean mouseReleased() {
        if (drawing == null) {
            return false;
        }

        Stroke stroke = current();
        drawing = null;

        // A frame or a cover-up that was only clicked has no size, and would be an
        // invisible entry that Undo then has to remove before anything visible.
        boolean empty = (tool == Tool.FRAME || tool == Tool.REDACT)
                && stroke.points().getFirst().equals(stroke.points().getLast());

        if (!empty) {
            strokes.add(stroke);
        }

        changed();
        return true;
    }

    private Stroke current() {
        List<Stroke.Point> points = drawing;

        if (tool != Tool.PEN && points.size() == 1) {
            points = List.of(points.getFirst(), points.getFirst());
        }

        return new Stroke(tool, tool == Tool.REDACT ? COVER : colour, WIDTHS[widthIndex], points);
    }

    private static Stroke.Point point(double x, double y, int[] preview) {
        return Stroke.Point.of((x - preview[0]) / preview[2], (y - preview[1]) / preview[3]);
    }

    private static boolean inside(double x, double y, int left, int top, int width, int height) {
        return x >= left && y >= top && x < left + width && y < top + height;
    }

    private int swatchX(int index) {
        return dockX + PAD + (index % 3) * SWATCH_STEP;
    }

    private int swatchRowY(int index) {
        return swatchY + (index / 3) * (SWATCH + GAP);
    }

    // ---- drawing ----

    /** Under the buttons, so it is drawn before them. */
    void extractPanel(GuiGraphicsExtractor graphics) {
        if (!open) {
            return;
        }

        int x0 = dockX;
        int y0 = dockY;
        int x1 = dockX + DOCK_WIDTH;
        int y1 = dockY + dockHeight;

        // Inventory-style: black edge, light top-left, dark bottom-right.
        graphics.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, OUTLINE);
        graphics.fill(x0, y0, x1, y1, PANEL);
        graphics.fill(x0, y0, x1 - 1, y0 + 1, PANEL_LIGHT);
        graphics.fill(x0, y0, x0 + 1, y1 - 1, PANEL_LIGHT);
        graphics.fill(x0 + 1, y1 - 1, x1, y1, PANEL_SHADOW);
        graphics.fill(x1 - 1, y0 + 1, x1, y1, PANEL_SHADOW);

        divider(graphics, undo.getY() - 3);
        divider(graphics, swatchY - 3);
        divider(graphics, thicknessY - 3);
    }

    private void divider(GuiGraphicsExtractor graphics, int y) {
        graphics.fill(dockX + PAD, y, dockX + DOCK_WIDTH - PAD, y + 1, PANEL_SHADOW);
        graphics.fill(dockX + PAD, y + 1, dockX + DOCK_WIDTH - PAD, y + 2, PANEL_LIGHT);
    }

    /** Over the buttons: icons, the selection, the palette and the widths. */
    void extractDecorations(GuiGraphicsExtractor graphics) {
        if (!open) {
            return;
        }

        for (Map.Entry<Tool, Button> entry : toolButtons.entrySet()) {
            Button button = entry.getValue();
            icon(graphics, ICONS.get(entry.getKey()), button.getX() + 5, button.getY() + 5, true);

            if (entry.getKey() == tool) {
                frame(graphics, button.getX(), button.getY(), BUTTON, BUTTON, SELECTED);
            }
        }

        icon(graphics, UNDO_ICON, undo.getX() + 5, undo.getY() + 5, undo.active);
        icon(graphics, CLEAR_ICON, clear.getX() + 5, clear.getY() + 5, clear.active);

        for (int i = 0; i < PALETTE.length; i++) {
            int x = swatchX(i);
            int y = swatchRowY(i);
            graphics.fill(x, y, x + SWATCH, y + SWATCH, OUTLINE);
            graphics.fill(x + 1, y + 1, x + SWATCH - 1, y + SWATCH - 1, ARGB.opaque(PALETTE[i]));

            if (PALETTE[i] == colour) {
                frame(graphics, x - 1, y - 1, SWATCH + 2, SWATCH + 2, SELECTED);
            }
        }

        for (int i = 0; i < WIDTHS.length; i++) {
            int x = swatchX(i);
            graphics.fill(x, thicknessY, x + SWATCH, thicknessY + SWATCH, SLOT);

            int bar = i + 1;
            int top = thicknessY + (SWATCH - bar) / 2;
            graphics.fill(x + 2, top, x + SWATCH - 2, top + bar, ARGB.opaque(colour));

            if (i == widthIndex) {
                frame(graphics, x - 1, thicknessY - 1, SWATCH + 2, SWATCH + 2, OUTLINE);
            }
        }
    }

    private static void icon(GuiGraphicsExtractor graphics, String[] rows, int x, int y, boolean enabled) {
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < rows[row].length(); column++) {
                if (rows[row].charAt(column) == '#') {
                    if (enabled) {
                        graphics.fill(x + column, y + row, x + column + 1, y + row + 1, ICON);
                    } else {
                        graphics.fill(x + column, y + row, x + column + 1, y + row + 1, ICON_OFF);
                    }
                }
            }
        }
    }

    private static void frame(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int argb) {
        graphics.fill(x, y, x + width, y + 1, ARGB.opaque(argb));
        graphics.fill(x, y + height - 1, x + width, y + height, ARGB.opaque(argb));
        graphics.fill(x, y, x + 1, y + height, ARGB.opaque(argb));
        graphics.fill(x + width - 1, y, x + width, y + height, ARGB.opaque(argb));
    }

    /**
     * The marks over the preview, at the preview's size in real pixels.
     *
     * <p>Not the GUI size: at GUI scale 3 a mark painted at GUI resolution would
     * be three pixels thick at its thinnest and look nothing like the upload.
     */
    void extractOverlay(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        if (strokes.isEmpty() && drawing == null) {
            return;
        }

        int scale = Math.max(1, minecraft.getWindow().getGuiScale());
        int pixelWidth = width * scale;
        int pixelHeight = height * scale;

        if (overlay == null || overlayWidth != pixelWidth || overlayHeight != pixelHeight) {
            release();
            overlay = new DynamicTexture(() -> AlaScreen.MOD_NAME + " marks", pixelWidth, pixelHeight, true);
            minecraft.getTextureManager().register(overlayId, overlay);
            overlayWidth = pixelWidth;
            overlayHeight = pixelHeight;
            dirty = true;
        }

        if (dirty) {
            NativeImage pixels = overlay.getPixels();
            pixels.fillRect(0, 0, pixelWidth, pixelHeight, 0);

            ImageCanvas canvas = new ImageCanvas(pixels);
            Rasterizer.paint(canvas, strokes);
            if (drawing != null) {
                Rasterizer.paint(canvas, current());
            }

            overlay.upload();
            dirty = false;
        }

        graphics.blit(overlayId, x, y, x + width, y + height, 0.0F, 1.0F, 0.0F, 1.0F);
    }

    /** Frees the overlay texture, and with it the image it owns. */
    void release() {
        if (overlay != null) {
            minecraft.getTextureManager().release(overlayId);
            overlay = null;
        }
    }
}
