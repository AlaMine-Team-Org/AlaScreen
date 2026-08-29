package day.alacraft.alascreen.mc;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import day.alacraft.alascreen.AlaScreen;
import net.minecraft.client.Minecraft;

/**
 * The client command tree, once, for every loader.
 *
 * <p>Generic in {@code S} on purpose, and that is the whole reason this class
 * exists. The two loaders hand out different command-source types — Fabric its
 * own {@code FabricClientCommandSource}, NeoForge the vanilla
 * {@code CommandSourceStack} — so the obvious implementation is to build the
 * tree twice, once per entrypoint. That is also how the two copies start to
 * differ: a subcommand added on one side, an argument renamed on the other, and
 * the mod behaves differently depending on which jar you installed.
 *
 * <p>None of the actions need the source. They need {@link Minecraft}, which is
 * the same on both. So the tree is written against raw Brigadier builders rather
 * than either loader's helper, and each entrypoint registers it into its own
 * dispatcher with three lines.
 */
public final class CommandTree {

    private CommandTree() {
    }

    /**
     * Build {@code /alascreen} and its subcommands.
     *
     * @param controller the flow every action delegates to
     * @param <S>        the loader's command-source type, inferred at the call site
     */
    public static <S> LiteralArgumentBuilder<S> build(ShareController controller) {
        return LiteralArgumentBuilder.<S>literal(AlaScreen.MOD_ID)
                .executes(context -> {
                    controller.captureAndShare(Minecraft.getInstance());
                    return 1;
                })
                .then(LiteralArgumentBuilder.<S>literal("list")
                        .executes(context -> {
                            controller.list(Minecraft.getInstance());
                            return 1;
                        }))
                .then(LiteralArgumentBuilder.<S>literal("help")
                        .executes(context -> {
                            controller.help(Minecraft.getInstance());
                            return 1;
                        }))
                // Run by the button on the vanilla F2 message, and usable by hand
                // for any file already in screenshots/. A bare `upload` takes the
                // newest one: the share button always passes a name, but when that
                // argument goes missing the player must get their screenshot, not
                // a parser error.
                .then(LiteralArgumentBuilder.<S>literal("upload")
                        .executes(context -> {
                            controller.uploadLatest(Minecraft.getInstance());
                            return 1;
                        })
                        .then(RequiredArgumentBuilder.<S, String>argument("file", StringArgumentType.string())
                                .executes(context -> {
                                    controller.uploadExisting(Minecraft.getInstance(),
                                            StringArgumentType.getString(context, "file"));
                                    return 1;
                                })))
                // Bare `delete <id>` only ASKS; the deletion itself is behind
                // `confirm`, so a mis-click on a chat line cannot destroy a link
                // other people may already hold.
                .then(LiteralArgumentBuilder.<S>literal("delete")
                        .then(RequiredArgumentBuilder.<S, String>argument("id", StringArgumentType.word())
                                .executes(context -> {
                                    controller.askDelete(Minecraft.getInstance(),
                                            StringArgumentType.getString(context, "id"));
                                    return 1;
                                })
                                .then(LiteralArgumentBuilder.<S>literal("confirm")
                                        .executes(context -> {
                                            controller.delete(Minecraft.getInstance(),
                                                    StringArgumentType.getString(context, "id"));
                                            return 1;
                                        }))
                                .then(LiteralArgumentBuilder.<S>literal("cancel")
                                        .executes(context -> {
                                            controller.cancelDelete(Minecraft.getInstance());
                                            return 1;
                                        }))));
    }
}
