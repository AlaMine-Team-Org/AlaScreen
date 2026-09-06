package day.alacraft.alascreen.mc;

import com.mojang.blaze3d.platform.NativeImage;
import day.alacraft.alascreen.AlaScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Owns the plaque's picture, and outlives the plaque on purpose.
 *
 * <p>{@link ShotToast} is handed to vanilla, and vanilla can throw it away
 * without telling us: {@code ToastManager.clear()} drops every toast
 * <em>without</em> calling {@code onFinishedRendering()}, and it runs on
 * {@code Hud.onDisconnected} — that is, every single time the player leaves a
 * world. An owner living inside the toast would therefore leak a
 * {@link NativeImage} and a GPU texture on every "played, went back to the
 * menu", which is the most ordinary thing a player does.
 *
 * <p>So the toast only ever raises a flag, and the release happens here, driven
 * by the client tick. Three conditions free the texture, and each covers a road
 * the others do not: the toast finished normally, the world went away, or too
 * much time passed with neither having happened (the plaque is not drawn at all
 * while the HUD is hidden with F1, so "it finished" is not guaranteed to arrive).
 *
 * <p>Never released from inside rendering: the frame's {@code BlitRenderState}
 * holds the texture view until the frame ends, so closing it there is a use of
 * freed GPU memory rather than an early cleanup.
 *
 * <p>"Is the plaque gone" is asked of the toast manager rather than guessed from
 * {@code minecraft.level}, and the difference is not academic: the manager still
 * holds a plaque queued behind five other toasts, while the level is null for
 * perfectly ordinary reasons. Freeing the picture while the plaque is still due
 * to be drawn would leave it blitting an unknown id — and an unknown id does not
 * draw nothing: the texture manager builds a placeholder for it, logs about it,
 * and keeps that bad entry for the rest of the session.
 */
public final class SharePrompt {

    /**
     * One id for every plaque, reused.
     *
     * <p>{@code TextureManager.register} closes whatever was registered under the
     * id before, so a second capture cannot leak the first one's texture, and the
     * registry does not accumulate an entry per screenshot.
     */
    private static final Identifier TEXTURE_ID =
            Identifier.fromNamespaceAndPath(AlaScreen.MOD_ID, "toast/preview");

    /**
     * The thumbnail is drawn 52 logical pixels wide, and GUI Scale 4 makes that
     * 208 real ones. Decoding a 4K frame down to this keeps the plaque sharp at
     * any scale while holding a quarter of a megabyte instead of thirty.
     */
    private static final int THUMBNAIL_MAX_SIDE = 256;

    /**
     * How long the texture may live when nothing tells us the plaque is gone.
     *
     * <p>Generous on purpose: it is a backstop for the case where the toast is
     * never rendered — F1 hides the HUD and vanilla skips the whole toast layer —
     * not the normal path. Five seconds of plaque plus the player's Notification
     * Time multiplier has to fit comfortably inside it.
     */
    private static final long HARD_EXPIRY_MS = 60_000L;

    private static ShotToast toast;
    private static String fileName;
    private static boolean textureRegistered;
    private static long expiresAtMs;

    private SharePrompt() {
    }

    /**
     * Read the saved screenshot, shrink it, and put the plaque on screen.
     *
     * <p>The read and the decode happen off the render thread. On the F2 path
     * there is no way around a disk read: vanilla closes the captured
     * {@link NativeImage} in its io pool the moment the file is written, so by
     * the time our hook sees the message the pixels are gone. That makes the
     * plaque appear a beat after the chat line rather than with it, which is a
     * property of the path, not a fault to fix.
     */
    public static void show(Minecraft minecraft, Path file, Component title, Component hint) {
        CompletableFuture
                .supplyAsync(() -> thumbnailOf(file), runnable ->
                        new Thread(runnable, "AlaScreen toast thumbnail").start())
                .whenComplete((thumbnail, failure) -> minecraft.execute(() -> {
                    if (failure != null || thumbnail == null) {
                        // No plaque, and nothing else changes: the chat line with
                        // the share button is already there and still works.
                        AlaScreen.LOGGER.debug("Could not build the toast thumbnail for {}.", file, failure);
                        return;
                    }

                    install(minecraft, thumbnail, file.getFileName().toString(), title, hint);
                }));
    }

    /**
     * The file the plaque is offering, while it is still on screen.
     *
     * <p>Empty once the plaque is gone, which is what makes the share key mean
     * "take a new shot" again rather than re-sharing something the player has
     * stopped looking at.
     */
    public static Optional<String> armedFileName() {
        return toast != null && toast.isShowing() && fileName != null
                ? Optional.of(fileName)
                : Optional.empty();
    }

    /**
     * Take the plaque down immediately.
     *
     * <p>Called before a capture, and the reason is the oldest complaint about
     * screenshot overlays there is: Java Edition photographs the HUD, so a plaque
     * still on screen ends up inside the next shot. ShareX has had that bug open
     * since 2014 because it cannot see the frame; we can, so this is a
     * requirement rather than a nicety.
     */
    public static void dismiss() {
        if (toast != null) {
            toast.dismiss();
        }
    }

    /**
     * Free the picture once the plaque is done with it. Call from the client tick.
     */
    public static void tick(Minecraft minecraft) {
        if (!textureRegistered) {
            return;
        }

        boolean gone = toast == null
                || toast.hasFinished()
                || !stillQueuedOrVisible(minecraft)
                || System.currentTimeMillis() > expiresAtMs;

        if (gone) {
            release(minecraft);
        }
    }

    /** Release everything, whatever state it is in. For shutdown. */
    public static void release(Minecraft minecraft) {
        if (textureRegistered) {
            // Releasing closes the DynamicTexture, which closes the NativeImage
            // it took ownership of. Nothing else may touch those pixels after
            // this point.
            minecraft.getTextureManager().release(TEXTURE_ID);
            textureRegistered = false;
        }

        toast = null;
        fileName = null;
    }

    /** True while the manager still has our plaque — visible, or waiting for a slot. */
    private static boolean stillQueuedOrVisible(Minecraft minecraft) {
        if (minecraft.gui == null || minecraft.gui.toastManager() == null) {
            return false;
        }

        return minecraft.gui.toastManager().getToast(ShotToast.class, ShotToast.token()) == toast;
    }

    private static void install(Minecraft minecraft, NativeImage thumbnail, String name,
                                Component title, Component hint) {
        if (minecraft.gui == null || minecraft.gui.toastManager() == null) {
            thumbnail.close();
            return;
        }

        // Registering under the same id closes the previous texture itself, so the
        // old picture cannot leak and the registry does not grow an entry per
        // screenshot.
        DynamicTexture texture = new DynamicTexture(() -> AlaScreen.MOD_NAME + " toast", thumbnail);
        minecraft.getTextureManager().register(TEXTURE_ID, texture);
        textureRegistered = true;

        ShotToast existing = minecraft.gui.toastManager().getToast(ShotToast.class, ShotToast.token());

        // The font is needed before the first frame: the plaque measures its own
        // height from the wrapped lines, and the manager asks for that height
        // while reserving slots.
        Font font = minecraft.font;

        if (existing != null) {
            // Update the plaque that is already up rather than queueing a second
            // one behind it: both would draw the same texture id, and freeing one
            // would pull the picture out from under the other.
            existing.reset(font, thumbnail.getWidth(), thumbnail.getHeight(), title, hint);
            toast = existing;
        } else {
            toast = new ShotToast(TEXTURE_ID, font, thumbnail.getWidth(), thumbnail.getHeight(), title, hint);
            minecraft.gui.toastManager().addToast(toast);
        }

        fileName = name;
        expiresAtMs = System.currentTimeMillis() + HARD_EXPIRY_MS;
    }

    /**
     * Decode the file and scale it down to something a plaque can hold.
     *
     * <p>Returns {@code null} rather than throwing when the file cannot be read:
     * the plaque is a convenience, and a screenshot that saved correctly must not
     * turn into an error message because its thumbnail failed.
     */
    private static NativeImage thumbnailOf(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            NativeImage full = NativeImage.read(bytes);

            int width = full.getWidth();
            int height = full.getHeight();

            if (width <= 0 || height <= 0) {
                full.close();
                return null;
            }

            if (width <= THUMBNAIL_MAX_SIDE && height <= THUMBNAIL_MAX_SIDE) {
                return full;
            }

            double scale = Math.min(THUMBNAIL_MAX_SIDE / (double) width, THUMBNAIL_MAX_SIDE / (double) height);

            int scaledWidth = Math.max(1, (int) Math.round(width * scale));
            int scaledHeight = Math.max(1, (int) Math.round(height * scale));

            NativeImage scaled = new NativeImage(scaledWidth, scaledHeight, false);

            try {
                full.resizeSubRectTo(0, 0, width, height, scaled);
            } finally {
                full.close();
            }

            return scaled;
        } catch (Exception e) {
            AlaScreen.LOGGER.debug("Could not read {} for the toast ({}).", file, e.getMessage());
            return null;
        }
    }
}
