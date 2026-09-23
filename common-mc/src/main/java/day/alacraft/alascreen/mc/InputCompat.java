package day.alacraft.alascreen.mc;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;

/**
 * Keyboard questions whose answer is spelled differently per Minecraft version.
 *
 * <p>Undo follows the LETTER Z in the player's layout, the way vanilla reads its
 * own Ctrl+C and Ctrl+V. On 26.2 {@link KeyEvent#key()} is already that letter
 * (a layout-aware GLFW code; vanilla's {@code isCopy} compares {@code input()}
 * with 67). 26.3 replaced GLFW with SDL3: {@code key()} became the physical key
 * position and the letter moved to {@code shortcutKey()}, which 26.2 does not
 * have. Reading the physical key on 26.3 would put undo on the key labelled Y on
 * a German keyboard and W on a French one, silently.
 *
 * <p>So this one method is written once per version. Everything else in the mod
 * compiles unchanged against both.
 *
 * <p>Never compare input codes with numeric literals: the constants in
 * {@link InputConstants} are {@code static final int}, the compiler copies their
 * VALUES into our bytecode, and 26.3 renumbered them (F7 296 -> 64, Z 90 -> 29,
 * left mouse button 0 -> 1). A literal keeps compiling and quietly stops
 * matching.
 */
public final class InputCompat {

    private InputCompat() {
    }

    /** Ctrl+Z, by the letter the player sees on the key. */
    public static boolean isUndo(KeyEvent event) {
        return event.hasControlDown() && event.key() == InputConstants.KEY_Z;
    }
}
