package day.alacraft.alascreen.clipboard;

import day.alacraft.alascreen.AlaScreen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Puts a PNG on the system clipboard, so it can be pasted as a picture.
 *
 * <p>The link is only half of what a player wants. Pasting the shot straight
 * into Discord means opening the link in a browser first and copying the image
 * from there — three steps around something already sitting on disk.
 *
 * <p><b>Why this is not two lines of AWT.</b> Minecraft sets
 * {@code java.awt.headless=true} in a static initialiser of
 * {@code net.minecraft.client.main.Main} (verified with {@code javap} against the
 * 26.2 jar, not remembered), because AWT and GLFW cannot both own the first
 * thread on macOS. So:
 *
 * <ul>
 *   <li><b>Windows and Linux</b> — the flag is put back before the first use.
 *       {@link java.awt.GraphicsEnvironment} caches its answer the first time it
 *       is asked, so this only works while nothing has asked yet: a scan of all
 *       3507 classes in the client jar found no reference to it, and the flip is
 *       done lazily, on the first copy, so a player who never uses the feature
 *       never has the JVM's state changed underneath them.</li>
 *   <li><b>macOS</b> — AWT stays off and {@code osascript} does the work. The two
 *       published mods that solve this ship a native bridge for it; a mod whose
 *       shared module is dependency-free should not grow one for a button.</li>
 * </ul>
 *
 * <p>Nothing here touches AWT directly: the AWT half lives in its own class so
 * that a runtime built without {@code java.desktop} fails as a
 * {@link LinkageError} at one known call site instead of at class load.
 */
public final class ImageClipboard {

    private ImageClipboard() {
    }

    /** The platforms that need different treatment — which is only macOS. */
    public enum Platform {
        MAC,
        OTHER;

        /** Classify an {@code os.name} value. */
        public static Platform of(String osName) {
            String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);

            return name.contains("mac") || name.contains("darwin") ? MAC : OTHER;
        }

        public static Platform current() {
            return of(System.getProperty("os.name"));
        }
    }

    /**
     * Copy the picture at {@code png} to the clipboard.
     *
     * <p>Call this off the render thread: the system clipboard can block while
     * another application decides to hand over ownership, and the game would
     * freeze for as long as it does.
     */
    public static CopyOutcome copy(Path png) {
        if (png == null || !Files.isRegularFile(png)) {
            return CopyOutcome.FILE_MISSING;
        }

        try {
            return Platform.current() == Platform.MAC
                    ? MacPasteboard.copy(png)
                    : AwtClipboard.copy(png);
        } catch (InterruptedException interrupted) {
            // Restore what the catch swallowed. A thread that is asked to stop
            // and then reports only "the clipboard refused" has lost the reason
            // it was stopping, and whoever asked never finds out it worked.
            Thread.currentThread().interrupt();

            return CopyOutcome.FAILED;
        } catch (LinkageError missingDesktop) {
            // A runtime assembled without java.desktop. Not a defect and not
            // worth a stack trace: the player is told the system cannot do it.
            AlaScreen.LOGGER.warn("This runtime has no desktop clipboard support ({}).",
                    missingDesktop.toString());

            return CopyOutcome.UNSUPPORTED;
        } catch (Throwable failure) {
            AlaScreen.LOGGER.warn("Could not copy {} to the clipboard.", png, failure);

            return CopyOutcome.FAILED;
        }
    }
}
