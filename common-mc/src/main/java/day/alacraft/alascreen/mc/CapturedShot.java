package day.alacraft.alascreen.mc;

import com.mojang.blaze3d.platform.NativeImage;

import java.nio.file.Path;
import java.util.Optional;

/**
 * One captured frame, already encoded, on its way to a decision.
 *
 * <p>Encoding happens before the player is asked rather than after, which is a
 * deliberate trade. It costs a few hundred milliseconds on a 4K shot that may
 * then be cancelled, and it buys three things worth more than that: the
 * confirmation screen can show the real file size, an oversized capture is
 * refused before the player is bothered with a question, and the ownership of
 * the native image stops being a puzzle — the bytes are already safe, so
 * whoever closes the image last cannot take the upload down with it.
 *
 * <p><b>Ownership.</b> {@code image} is native memory. Passing it to a
 * {@link net.minecraft.client.renderer.texture.DynamicTexture} hands the texture
 * ownership, and the texture closes it. So exactly one of two things must
 * happen: the confirmation screen takes it, or {@link #close()} is called. Both
 * would be a double free, which in this case is a JVM crash rather than an
 * exception.
 *
 * @param png      the encoded image, the thing that is actually uploaded
 * @param width    pixels
 * @param height   pixels
 * @param fileName what to call it, both locally and on the site
 * @param savedTo  where it was kept, when the player asked for a local copy
 * @param image    the decoded frame, alive only for the preview
 */
public record CapturedShot(
        byte[] png,
        int width,
        int height,
        String fileName,
        Optional<Path> savedTo,
        NativeImage image) {

    public long bytes() {
        return png.length;
    }

    /** Release the native frame. Only when nothing else has taken it. */
    public void close() {
        if (image != null && !image.isClosed()) {
            image.close();
        }
    }
}
