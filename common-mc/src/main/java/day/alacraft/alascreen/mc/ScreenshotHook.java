package day.alacraft.alascreen.mc;

import day.alacraft.alascreen.AlaScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * Adds a share button to the screenshot message vanilla already prints.
 *
 * <p>This is what makes the mod work without being configured, and that turned
 * out to matter more than any feature in it. A key binding that ships unbound is
 * defensible reasoning — every free key belongs to somebody's mod — and it is
 * still the wrong default, because the hand goes to F2. Press it, get the
 * ordinary vanilla message, conclude the mod is broken, uninstall.
 *
 * <p>So F2 stays exactly what it was: the same capture, the same file, the same
 * sentence. One bracketed action is appended to the end of it. Nothing is sent
 * anywhere until that word is clicked.
 *
 * <p>The loader-specific half is a mixin of a dozen lines that hands the
 * consumer here; everything the player sees is in this class, so the second
 * loader inherits it for free.
 */
public final class ScreenshotHook {

    /** The vanilla key for "Saved screenshot as %s". */
    private static final String SUCCESS_KEY = "screenshot.success";

    private ScreenshotHook() {
    }

    /**
     * Wrap the consumer vanilla will hand its message to.
     *
     * <p>Wrapping rather than injecting after the fact, because this way the
     * message reaches the chat exactly once, from vanilla's own call, with our
     * addition already on it — there is no window in which the plain line has
     * been shown and the button has not.
     */
    public static Consumer<Component> decorate(Consumer<Component> original) {
        return message -> original.accept(withShareAction(message));
    }

    /**
     * Append the share action, when the message is the one we mean.
     *
     * <p>Failures here are swallowed on purpose: this runs inside vanilla's
     * screenshot path, and a mod that turns "screenshot saved" into a crash
     * would be far worse than one that quietly fails to add a button.
     */
    private static Component withShareAction(Component message) {
        try {
            if (!(message.getContents() instanceof TranslatableContents contents)
                    || !SUCCESS_KEY.equals(contents.getKey())) {
                // The failure message, or a future message we do not know.
                // Leave it alone.
                return message;
            }

            File file = findFile(message);

            if (file == null || file.getName().isBlank()) {
                // No button at all rather than one that runs an incomplete
                // command. A button that answers "unknown or incomplete
                // command" is worse than a message with no button: it looks
                // like the mod is broken rather than absent.
                return message;
            }

            // A fresh, unstyled parent. Appending to the vanilla component would
            // make our button inherit its click event — the one that opens the
            // file in the system viewer — so the button would look like a button
            // and do something else entirely.
            return Component.empty()
                    .append(message)
                    .append(ChatFormat.space())
                    .append(ChatFormat.button("alascreen.button.share",
                            "alascreen.button.share.hover",
                            uploadCommand(file.getName())));
        } catch (RuntimeException e) {
            AlaScreen.LOGGER.debug("Could not decorate the screenshot message ({}).", e.getMessage());

            return message;
        }
    }

    /**
     * Find the file the message is about.
     *
     * <p>Vanilla builds the file name as a component carrying a
     * {@link ClickEvent.OpenFile} with the full path, so the message already
     * knows which screenshot it refers to. Reading it from there beats guessing
     * at "the newest file in the folder", which is wrong the moment two captures
     * land in the same second or a screenshot arrives from somewhere else.
     */
    private static File findFile(Component message) {
        Deque<Component> pending = new ArrayDeque<>();
        pending.add(message);

        while (!pending.isEmpty()) {
            Component current = pending.removeFirst();

            if (current.getStyle() != null
                    && current.getStyle().getClickEvent() instanceof ClickEvent.OpenFile open) {
                return open.file();
            }

            pending.addAll(current.getSiblings());

            if (current.getContents() instanceof TranslatableContents contents) {
                for (Object argument : contents.getArgs()) {
                    if (argument instanceof Component child) {
                        pending.add(child);
                    }
                }
            }
        }

        return null;
    }

    /**
     * The command the share button runs, with the file name quoted.
     *
     * <p>Quoted because the name travels through a Brigadier parser as a chat
     * command, and an unquoted argument has to survive whatever the filesystem
     * put in it. A vanilla screenshot name is tame, but this button also carries
     * names from resource packs, other mods and the player's own renames — and
     * the failure is silent in the worst way: the argument is dropped and the
     * command arrives as a bare "upload", which Brigadier reports as an
     * incomplete command with no hint of why.
     */
    static String uploadCommand(String fileName) {
        String escaped = fileName.replace("\\", "\\\\").replace("\"", "\\\"");

        return "alascreen upload \"" + escaped + "\"";
    }
}
