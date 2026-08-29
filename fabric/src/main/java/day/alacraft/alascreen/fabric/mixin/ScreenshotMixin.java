package day.alacraft.alascreen.fabric.mixin;

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
 * {@link ScreenshotHook}, which knows no loader. When NeoForge lands, its mixin
 * is this file with a different package.
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
