package day.alacraft.alascreen.mc;

import com.mojang.blaze3d.platform.NativeImage;
import day.alacraft.alascreen.AlaScreen;
import day.alacraft.alascreen.annotate.Rasterizer;
import day.alacraft.alascreen.annotate.Stroke;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Takes the frame and turns it into bytes.
 *
 * <p>Two threads are involved and mixing them up is the classic way to freeze a
 * game: the GPU read has to happen on the render thread, and the PNG encode —
 * hundreds of milliseconds for a large frame — must not. Everything public here
 * returns a future so the caller cannot accidentally wait on the wrong one.
 */
public final class ScreenCapture {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

    private ScreenCapture() {
    }

    /**
     * Capture the current frame and encode it.
     *
     * <p>Must be called on the render thread — {@code takeScreenshot} reads the
     * main render target, which only exists there.
     *
     * @param saveTo where to keep the file, or empty to encode without leaving
     *               anything behind
     * @param io     the executor the encode runs on; never the render thread
     */
    public static CompletableFuture<CapturedShot> capture(Minecraft minecraft,
                                                          Optional<Path> saveTo,
                                                          Executor io) {
        CompletableFuture<CapturedShot> result = new CompletableFuture<>();

        try {
            Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), image ->
                    // The consumer hands us a live NativeImage on whichever
                    // thread finished the download. Nothing heavy happens here:
                    // the work is handed straight to the io executor.
                    CompletableFuture
                            .supplyAsync(() -> encode(image, saveTo), io)
                            .whenComplete((shot, failure) -> {
                                if (failure != null) {
                                    // The image never reached a CapturedShot, so
                                    // nobody else will free it.
                                    if (!image.isClosed()) {
                                        image.close();
                                    }
                                    result.completeExceptionally(failure);
                                } else {
                                    result.complete(shot);
                                }
                            }));
        } catch (RuntimeException e) {
            AlaScreen.LOGGER.error("Could not capture the screen.", e);
            result.completeExceptionally(e);
        }

        return result;
    }

    /**
     * Write the PNG once and read it back, rather than encoding twice.
     *
     * <p>{@link NativeImage} has no {@code toByteArray}: the only encoder it
     * exposes writes to a file. Since the mod keeps a local copy by default
     * anyway, that write is not overhead — it is the copy the player expects to
     * find in {@code screenshots/}. When they have turned the local copy off we
     * still go through a file, in the system temporary directory, and delete it
     * immediately.
     */
    private static CapturedShot encode(NativeImage image, Optional<Path> directory) {
        String fileName = fileName();
        Path target = null;
        boolean temporary = directory.isEmpty();

        try {
            if (temporary) {
                target = Files.createTempFile("alascreen-", ".png");
            } else {
                // createDirectories on the DIRECTORY, not on its parent. The
                // parameter is `screenshots/`, so asking for its parent created
                // the game folder — which already existed — and left
                // `screenshots/` missing. The first capture in a fresh instance
                // then failed with NoSuchFileException, and the second worked,
                // because pressing F2 in between had created the folder. A bug
                // that hides the moment anyone takes an ordinary screenshot.
                Files.createDirectories(directory.get());
                target = directory.get().resolve(fileName);
            }

            image.writeToFile(target);
            byte[] png = Files.readAllBytes(target);

            return new CapturedShot(
                    png,
                    image.getWidth(),
                    image.getHeight(),
                    fileName,
                    temporary ? Optional.empty() : Optional.of(target),
                    image);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the screenshot: " + e.getMessage(), e);
        } finally {
            if (temporary && target != null) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException e) {
                    // A leftover file in the temp directory is untidy, not
                    // harmful, and saying so must not fail the capture.
                    AlaScreen.LOGGER.debug("Could not remove the temporary capture {}.", target);
                }
            }
        }
    }

    /**
     * Shrink a capture until it fits the site's byte limit.
     *
     * <p>Needed because "too large, try again" is not an answer a player can act
     * on. Their monitor is the size it is: an ultrawide or a 4K panel produces a
     * PNG of fifteen to twenty megabytes, every single time, and refusing those
     * would mean the mod works for small screens and not for good ones.
     *
     * <p>Both published limits are respected, and the second one matters more
     * than it looks. A screenshot of a night sky compresses to almost nothing
     * and can pass the byte limit at 33 megapixels — which the site refuses,
     * because decoding it would exhaust the server's memory. Fitting only the
     * byte limit would send a shot that was always going to be rejected.
     *
     * <p><b>The file on disk is never touched.</b> The local copy stays at full
     * resolution — that is the player's screenshot. Only the upload is scaled,
     * and only when it has to be.
     *
     * <p>Iterative rather than one calculation: PNG size depends on the content,
     * not just the pixel count — a screenshot of the sky compresses to a fraction
     * of one full of foliage — so the honest approach is to shrink, re-encode and
     * look. Each pass targets the limit with a margin and gives up a little more,
     * and four passes take a screenshot of any size below it.
     *
     * @param maxPixels the site's pixel cap; 0 means it did not publish one
     * @return the original when it already fits, otherwise a scaled copy; the
     *         input's image is closed when a copy is made
     */
    public static CapturedShot fitToLimit(CapturedShot shot, long maxBytes, long maxPixels, Path workDirectory) {
        if (!exceeds(shot, maxBytes, maxPixels)) {
            return shot;
        }

        NativeImage current = shot.image();
        CapturedShot best = shot;

        for (int attempt = 0; attempt < 4 && exceeds(best, maxBytes, maxPixels); attempt++) {
            // Ratio of the areas, so the linear factor is its square root. The
            // 0.9 leaves room for the fact that a smaller image compresses a
            // little less efficiently per pixel than the estimate suggests.
            // Whichever limit is further away decides how much to shrink; both
            // are ratios of AREA, so the linear factor is the square root.
            double factor = 1.0;

            if (maxBytes > 0 && best.bytes() > maxBytes) {
                factor = Math.min(factor, Math.sqrt((double) maxBytes / best.bytes()) * 0.9);
            }

            if (maxPixels > 0) {
                long pixels = (long) best.width() * best.height();

                if (pixels > maxPixels) {
                    // No safety margin here: pixels are exact arithmetic, unlike
                    // the size of a PNG, which depends on what is in the frame.
                    factor = Math.min(factor, Math.sqrt((double) maxPixels / pixels));
                }
            }

            int width = Math.max(320, (int) Math.round(current.getWidth() * factor));
            int height = Math.max(180, (int) Math.round(current.getHeight() * factor));

            if (width >= current.getWidth() || height >= current.getHeight()) {
                break;
            }

            NativeImage scaled = new NativeImage(width, height, false);
            current.resizeSubRectTo(0, 0, current.getWidth(), current.getHeight(), scaled);

            byte[] png;
            try {
                png = encodeToBytes(scaled, workDirectory);
            } catch (IOException e) {
                scaled.close();
                AlaScreen.LOGGER.warn("Could not re-encode the scaled capture ({}).", e.getMessage());
                break;
            }

            // The previous attempt's image is only ours to close from the second
            // pass onwards; on the first, it still belongs to the caller's shot.
            if (current != shot.image()) {
                current.close();
            }

            current = scaled;
            best = new CapturedShot(png, width, height, shot.fileName(), shot.savedTo(), scaled);
        }

        if (best != shot) {
            // The full-size frame is not needed any more: the bytes are encoded
            // and the preview will show the scaled image that is actually sent.
            shot.close();
        }

        return best;
    }

    /**
     * A private copy of a frame, taken on the render thread.
     *
     * <p>The preview texture owns the original and frees it the moment the
     * confirmation screen closes, which is the same moment the marks are needed.
     */
    public static NativeImage copyOf(NativeImage source) {
        NativeImage copy = new NativeImage(source.format(), source.getWidth(), source.getHeight(), false);
        copy.copyFrom(source);
        return copy;
    }

    /**
     * Burn the marks into {@code frame} and encode it. Off the render thread.
     *
     * <p>When the shot has a file on disk, the marked picture is written next to
     * it as {@code <name>-marked.png} and the original is left alone: that is the
     * player's screenshot. The upload then points at the marked file, so
     * {@code [copy image]} puts on the clipboard what was actually shared.
     *
     * @param frame a copy from {@link #copyOf}; the returned shot owns it
     */
    public static CapturedShot mark(CapturedShot shot, NativeImage frame, List<Stroke> marks, Path workDirectory) {
        try {
            Rasterizer.paint(new ImageCanvas(frame), marks);

            if (shot.savedTo().isPresent()) {
                String name = markedName(shot.fileName());
                Path target = shot.savedTo().get().resolveSibling(name);
                frame.writeToFile(target);

                return new CapturedShot(Files.readAllBytes(target), frame.getWidth(), frame.getHeight(),
                        name, Optional.of(target), frame);
            }

            return new CapturedShot(encodeToBytes(frame, workDirectory), frame.getWidth(), frame.getHeight(),
                    shot.fileName(), Optional.empty(), frame);
        } catch (IOException | RuntimeException e) {
            frame.close();
            throw new IllegalStateException("Could not apply the marks: " + e.getMessage(), e);
        }
    }

    static String markedName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0
                ? fileName.substring(0, dot) + "-marked" + fileName.substring(dot)
                : fileName + "-marked.png";
    }

    /** True when a capture breaks either published limit. */
    private static boolean exceeds(CapturedShot shot, long maxBytes, long maxPixels) {
        if (maxBytes > 0 && shot.bytes() > maxBytes) {
            return true;
        }

        return maxPixels > 0 && (long) shot.width() * shot.height() > maxPixels;
    }

    /** Encode an image to PNG bytes through a temporary file, as NativeImage requires. */
    private static byte[] encodeToBytes(NativeImage image, Path workDirectory) throws IOException {
        Files.createDirectories(workDirectory);
        Path temporary = Files.createTempFile(workDirectory, "alascreen-scaled-", ".png");

        try {
            image.writeToFile(temporary);

            return Files.readAllBytes(temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * The vanilla naming shape, so shared and unshared shots sort together in
     * the folder and nothing looks foreign next to an F2 screenshot.
     */
    private static String fileName() {
        return LocalDateTime.now().format(FILE_STAMP) + ".png";
    }
}
