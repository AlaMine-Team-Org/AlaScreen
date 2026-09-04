package day.alacraft.alascreen.clipboard;

import day.alacraft.alascreen.AlaScreen;

import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * The clipboard on everything except macOS.
 *
 * <p>Separate class on purpose: every AWT type this mod touches is named here
 * and nowhere else, so a runtime without {@code java.desktop} throws at the one
 * call into this class rather than while loading the caller.
 */
final class AwtClipboard {

    private AwtClipboard() {
    }

    static CopyOutcome copy(Path png) throws Exception {
        // Minecraft's own Main sets this to true before anything else runs.
        // Putting it back is only effective while nothing has asked
        // GraphicsEnvironment yet — it caches the answer for the life of the JVM.
        String previous = System.getProperty("java.awt.headless");
        System.setProperty("java.awt.headless", "false");

        if (GraphicsEnvironment.isHeadless()) {
            // Something read the flag before us — another mod using AWT without
            // clearing it first. Nothing can be done now, and saying so is
            // better than a stack trace the player cannot act on.
            //
            // Put the property back: leaving "false" behind a cached "true" is
            // worse than either, because anything that reads the property rather
            // than asking GraphicsEnvironment now believes AWT is available and
            // walks into a HeadlessException of its own.
            if (previous == null) {
                System.clearProperty("java.awt.headless");
            } else {
                System.setProperty("java.awt.headless", previous);
            }

            AlaScreen.LOGGER.warn("AWT is already headless; the picture cannot go on the clipboard.");

            return CopyOutcome.UNSUPPORTED;
        }

        BufferedImage image = ImageIO.read(png.toFile());

        if (image == null) {
            // No decoder claimed the file. It is a PNG we wrote ourselves, so
            // this means the file is damaged rather than the wrong format.
            AlaScreen.LOGGER.warn("Nothing could decode {} as an image.", png);

            return CopyOutcome.FAILED;
        }

        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImageSelection(image), null);

        return CopyOutcome.COPIED;
    }

    /**
     * One image, offered under the one flavour every clipboard understands.
     *
     * <p>AWT has no ready-made transferable for images, unlike the one it ships
     * for strings.
     */
    private record ImageSelection(Image image) implements Transferable {

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!DataFlavor.imageFlavor.equals(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }

            return image;
        }
    }
}
