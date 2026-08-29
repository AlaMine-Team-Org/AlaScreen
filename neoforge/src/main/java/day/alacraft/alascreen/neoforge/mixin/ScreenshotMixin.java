package day.alacraft.alascreen.neoforge.mixin;

import day.alacraft.alascreen.mc.ScreenshotHook;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Consumer;

/**
 * Makes F2 offer the share button, without changing what F2 does.
 *
 * <p>Deliberately the smallest thing that can work: it swaps the consumer
 * vanilla is about to hand its message to, and everything else lives in
 * {@link ScreenshotHook}.
 *
 * <p>One of these per loader, and that is deliberate rather than a shortcut.
 * Sharing the class would mean putting the Mixin library on the classpath of the
 * module whose entire point is that no loader is on it — and the two loaders
 * process mixins differently enough (config format, annotation processing,
 * refmaps) that the shared version would be a guess about both.
 *
 * <p>What matters is that it stays this thin. Everything the injection actually
 * DOES lives in {@link ScreenshotHook}, shared: the NeoForge copy of this file
 * differs by its package and nothing else, so a fix to the behaviour is written
 * once regardless.
 *
 * <p><b>Why this injection point.</b> The message and the file name are built
 * inside a synthetic lambda, whose name changes whenever Mojang recompiles the
 * class — an injection there would break on a version that changed nothing we
 * care about. The three-argument {@code grab} is public, stable, and on the F2
 * path, and the argument we need is right there in the call it makes.
 */
@Mixin(Screenshot.class)
public class ScreenshotMixin {

    @ModifyArg(
            method = "grab(Ljava/io/File;Lcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Screenshot;grab(Ljava/io/File;Ljava/lang/String;"
                            + "Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V"),
            index = 4)
    private static Consumer<Component> alascreen$addShareAction(Consumer<Component> original) {
        return ScreenshotHook.decorate(original);
    }
}
