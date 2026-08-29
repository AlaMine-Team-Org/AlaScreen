package day.alacraft.alascreen.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import day.alacraft.alascreen.AlaScreen;
import day.alacraft.alascreen.BuildInfo;
import day.alacraft.alascreen.Config;
import day.alacraft.alascreen.UploadService;
import day.alacraft.alascreen.api.AlaScreenApi;
import day.alacraft.alascreen.history.UploadHistory;
import day.alacraft.alascreen.mc.ShareController;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import com.mojang.brigadier.arguments.StringArgumentType;

import java.nio.file.Path;

/**
 * Fabric entrypoint.
 *
 * <p>Thin on purpose: it locates the game's directories, builds the shared
 * service out of them, registers one key binding and one command, and gets out
 * of the way. Adding NeoForge means writing this file again for that platform
 * and nothing else — everything a player interacts with already lives in the
 * shared modules.
 *
 * <p>{@code ClientModInitializer}, not {@code ModInitializer}: this mod has no
 * business on a dedicated server, and declaring it client-only is what lets it
 * be used on any server, vanilla ones included.
 */
public class AlaScreenFabric implements ClientModInitializer {

    private static Config config;
    private static ShareController controller;

    @Override
    public void onInitializeClient() {
        FabricLoader loader = FabricLoader.getInstance();

        Path configFile = loader.getConfigDir().resolve(AlaScreen.MOD_ID + ".json");

        // Everything the mod writes hangs off the loader's config directory, the
        // same one the config file came from. Building this from the game
        // directory instead is right only by coincidence: an instance manager
        // that moves the config directory would leave the delete tokens in a
        // folder the player never chose — possibly one that is synced or shared.
        Path dataDirectory = loader.getConfigDir().resolve(AlaScreen.MOD_ID);
        Path screenshots = loader.getGameDir().resolve("screenshots");

        config = Config.load(configFile);

        AlaScreenApi api = buildApi(config, minecraftVersion(loader));

        UploadService service = new UploadService(
                config,
                api,
                new UploadHistory(dataDirectory.resolve("history.json")),
                minecraftVersion(loader),
                "fabric");

        controller = new ShareController(config, service, configFile, screenshots);

        KeyMapping shareKey = registerKey();

        // Poll at the end of the tick rather than reacting inside an input
        // handler: consumeClick() is the vanilla way to ask "was this pressed
        // since I last looked", and it is what stops a held key from firing
        // every frame.
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            while (shareKey.consumeClick()) {
                controller.captureAndShare(minecraft);
            }
        });

        registerCommands();

        // Ask the site for its limits once the client is up rather than during
        // initialisation: a network call while mods are still loading delays
        // startup for something nobody has asked for yet.
        ClientLifecycleEvents.CLIENT_STARTED.register(minecraft -> service.warmUp());

        // Let go of the HTTP client and its threads while there is still a
        // shutdown to do it in, so an upload in flight gets its few seconds of
        // grace instead of dying with the process.
        ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> api.close());

        AlaScreen.LOGGER.info("{} {} ready - press the Ala Screen key to share a screenshot with {}",
                AlaScreen.MOD_NAME, BuildInfo.version(), config.apiBaseUrl);
        AlaScreen.LOGGER.info("Config: {}", configFile);
    }

    /**
     * The key binding, in its own category.
     *
     * <p><b>F7 by default.</b> The first version shipped unbound, on the
     * reasoning that every free key belongs to somebody's mod — defensible, and
     * wrong in practice: a tool that does nothing until it is configured reads
     * as broken.
     *
     * <p>F7 rather than a letter, and rather than the obvious F6. Checked
     * against the 26.2 jar rather than remembered: vanilla binds F2 (screenshot),
     * F3 and F4 (debug), F5 (perspective), F11 (fullscreen) — and **F6, which is
     * {@code key.debug.debug}**. F7 is free, sits next to the screenshot key it
     * belongs with, and function keys are far less contested by other mods than
     * letters like G, K or V.
     *
     * <p>Players who disagree rebind it; the point is that they do not have to.
     */
    private static KeyMapping registerKey() {
        // 26.2 turned key categories into Identifier-backed records; the old
        // string form does not compile any more.
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(AlaScreen.MOD_ID, "main"));

        return KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.alascreen.capture",
                InputConstants.Type.KEYSYM,
                org.lwjgl.glfw.GLFW.GLFW_KEY_F7,
                category));
    }

    /**
     * {@code /alascreen} and its subcommands, registered as a CLIENT command.
     *
     * <p>Client-side means the server never sees it: it works in singleplayer,
     * on a vanilla server and on a server that has never heard of this mod,
     * which is the entire point of a client tool.
     */
    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal(AlaScreen.MOD_ID)
                        .executes(context -> {
                            controller.captureAndShare(Minecraft.getInstance());
                            return 1;
                        })
                        .then(ClientCommands.literal("list")
                                .executes(context -> {
                                    controller.list(Minecraft.getInstance());
                                    return 1;
                                }))
                        .then(ClientCommands.literal("help")
                                .executes(context -> {
                                    controller.help(Minecraft.getInstance());
                                    return 1;
                                }))
                        // Run by the button on the vanilla F2 message, and
                        // usable by hand for any file already in screenshots/.
                        .then(ClientCommands.literal("upload")
                                // Bare `upload` takes the newest screenshot. The
                                // share button always passes a name, but when
                                // that argument goes missing the player must get
                                // their screenshot, not a parser error.
                                .executes(context -> {
                                    controller.uploadLatest(Minecraft.getInstance());
                                    return 1;
                                })
                                .then(ClientCommands.argument("file", StringArgumentType.string())
                                        .executes(context -> {
                                            controller.uploadExisting(Minecraft.getInstance(),
                                                    StringArgumentType.getString(context, "file"));
                                            return 1;
                                        })))
                        // Bare `delete <id>` only ASKS; the deletion itself is
                        // behind `confirm`, so a mis-click on a chat line cannot
                        // destroy a link other people may already hold.
                        .then(ClientCommands.literal("delete")
                                .then(ClientCommands.argument("id", StringArgumentType.word())
                                        .executes(context -> {
                                            controller.askDelete(Minecraft.getInstance(),
                                                    StringArgumentType.getString(context, "id"));
                                            return 1;
                                        })
                                        .then(ClientCommands.literal("confirm")
                                                .executes(context -> {
                                                    controller.delete(Minecraft.getInstance(),
                                                            StringArgumentType.getString(context, "id"));
                                                    return 1;
                                                }))
                                        .then(ClientCommands.literal("cancel")
                                                .executes(context -> {
                                                    controller.cancelDelete(Minecraft.getInstance());
                                                    return 1;
                                                }))))));
    }

    /**
     * Build the API client, surviving a typo in the config.
     *
     * <p>{@link Config} deliberately never fails the game over a broken config
     * file, but the URL check lives in the client's builder — so a missing
     * {@code https://} would throw out of {@code onInitializeClient()} and
     * Fabric would refuse to load the mod at all. Losing uploads to a typo is a
     * fair trade; losing the game is not.
     */
    private static AlaScreenApi buildApi(Config config, String minecraftVersion) {
        String userAgent = AlaScreenApi.userAgent(BuildInfo.version(), minecraftVersion, "fabric");

        try {
            return AlaScreenApi.builder(config.apiBaseUrl)
                    .userAgent(userAgent)
                    .source(BuildInfo.sourceTag())
                    .apiToken(config.apiToken)
                    .build();
        } catch (RuntimeException e) {
            AlaScreen.LOGGER.error(
                    "apiBaseUrl in the config is not a usable URL ({}): \"{}\". Falling back to {} - "
                            + "fix the config and restart to use your own instance.",
                    e.getMessage(), config.apiBaseUrl, AlaScreen.DEFAULT_API_BASE_URL);

            config.apiBaseUrl = AlaScreen.DEFAULT_API_BASE_URL;

            return AlaScreenApi.builder(AlaScreen.DEFAULT_API_BASE_URL)
                    .userAgent(userAgent)
                    .source(BuildInfo.sourceTag())
                    .apiToken(config.apiToken)
                    .build();
        }
    }

    /** The running Minecraft version, for the User-Agent we identify ourselves with. */
    private static String minecraftVersion(FabricLoader loader) {
        return loader.getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    public static Config config() {
        return config;
    }
}
