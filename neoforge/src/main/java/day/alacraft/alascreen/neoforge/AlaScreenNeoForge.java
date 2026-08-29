package day.alacraft.alascreen.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import day.alacraft.alascreen.AlaScreen;
import day.alacraft.alascreen.BuildInfo;
import day.alacraft.alascreen.Config;
import day.alacraft.alascreen.UploadService;
import day.alacraft.alascreen.api.AlaScreenApi;
import day.alacraft.alascreen.history.UploadHistory;
import day.alacraft.alascreen.mc.CommandTree;
import day.alacraft.alascreen.mc.ShareController;
import net.minecraft.SharedConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;

/**
 * NeoForge entrypoint.
 *
 * <p>The counterpart of {@code day.alacraft.alascreen.fabric.AlaScreenFabric},
 * and deliberately its mirror image: the same steps in the same order, differing
 * only where the loaders genuinely differ. Everything a player interacts with —
 * the command tree, the capture, the confirmation screen, the translations, the
 * history file — lives in {@code common} and {@code common-mc} and is compiled
 * into both jars from the same source.
 *
 * <p>That is what makes a fix a fix for both loaders. When these two files start
 * to drift, the mod has quietly become two mods.
 *
 * <p><b>What actually differs from the Fabric side, and nothing else:</b>
 * <ol>
 *   <li>the paths come from {@code FMLPaths} rather than {@code FabricLoader};</li>
 *   <li>the key mapping is registered through an event, and its <b>category has
 *       to be registered too</b> — Fabric derives the category from the
 *       Identifier alone, NeoForge wants it declared;</li>
 *   <li>the command dispatcher carries the vanilla source type, so the shared
 *       tree is instantiated with a different type parameter;</li>
 *   <li>two buses: registration events arrive on the mod bus, per-tick events on
 *       the game bus.</li>
 * </ol>
 */
@Mod(value = AlaScreen.MOD_ID, dist = net.neoforged.api.distmarker.Dist.CLIENT)
public class AlaScreenNeoForge {

    private static Config config;
    private static ShareController controller;
    private static AlaScreenApi api;

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(AlaScreen.MOD_ID, "main"));

    /**
     * F7 by default, matching Fabric. Verified free against the 26.2 jar: vanilla
     * holds F2 (screenshot), F3 and F4 (debug), F5 (perspective), F11 (fullscreen)
     * and — easy to get wrong — F6, which is {@code key.debug.debug}.
     */
    private static final KeyMapping SHARE_KEY = new KeyMapping(
            "key.alascreen.capture",
            InputConstants.Type.KEYSYM,
            org.lwjgl.glfw.GLFW.GLFW_KEY_F7,
            CATEGORY);

    public AlaScreenNeoForge(IEventBus modBus) {
        Path configFile = FMLPaths.CONFIGDIR.get().resolve(AlaScreen.MOD_ID + ".json");
        Path screenshots = FMLPaths.GAMEDIR.get().resolve("screenshots");

        // Same reasoning as the Fabric side: everything the mod writes hangs off
        // the loader's config directory, the one the config file itself came
        // from, so an instance manager that moves it takes the delete tokens
        // along instead of stranding them in a folder nobody chose.
        Path dataDirectory = FMLPaths.CONFIGDIR.get().resolve(AlaScreen.MOD_ID);

        config = Config.load(configFile);

        api = buildApi(config, minecraftVersion());

        UploadService service = new UploadService(
                config,
                api,
                new UploadHistory(dataDirectory.resolve("history.json")),
                minecraftVersion(),
                "neoforge");

        controller = new ShareController(config, service, configFile, screenshots);

        modBus.addListener(AlaScreenNeoForge::registerKeys);
        NeoForge.EVENT_BUS.register(AlaScreenNeoForge.class);

        // Ask the site for its limits once, rather than discovering them during
        // the first upload of the session. No image, no identifier.
        service.warmUp();

        AlaScreen.LOGGER.info("{} {} ready - press the Ala Screen key to share a screenshot with {}",
                AlaScreen.MOD_NAME, BuildInfo.version(), config.apiBaseUrl);
        AlaScreen.LOGGER.info("Config: {}", configFile);
    }

    /**
     * The category is registered explicitly here, unlike on Fabric.
     *
     * <p>{@code KeyMapping.Category.register} gives the category an identity, but
     * NeoForge builds the controls screen from the categories this event was
     * told about. Registering only the mapping leaves the binding in the list
     * under a category the screen never learned, which is how a binding ends up
     * looking like it belongs to nothing.
     */
    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(SHARE_KEY);
    }

    /**
     * {@code /alascreen}, from the same tree the Fabric side registers.
     *
     * <p>The dispatcher here carries vanilla's {@code CommandSourceStack} while
     * Fabric's carries its own type — which is exactly why
     * {@link CommandTree#build} is generic instead of written twice.
     */
    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(CommandTree.build(controller));
    }

    /**
     * Poll at the end of the tick, as on Fabric: {@code consumeClick()} is the
     * vanilla way to ask "was this pressed since I last looked", and it is what
     * stops a held key from firing every frame.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        while (SHARE_KEY.consumeClick()) {
            controller.captureAndShare(net.minecraft.client.Minecraft.getInstance());
        }
    }

    /**
     * Build the API client, surviving a typo in the config.
     *
     * <p>{@link Config} deliberately never fails the game over a broken config
     * file, but the URL check lives in the client's builder — so a missing
     * {@code https://} would throw out of the constructor and the loader would
     * refuse to load the mod at all. Losing uploads to a typo is a fair trade;
     * losing the game is not.
     */
    private static AlaScreenApi buildApi(Config config, String minecraftVersion) {
        String userAgent = AlaScreenApi.userAgent(BuildInfo.version(), minecraftVersion, "neoforge");

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
    private static String minecraftVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

    public static Config config() {
        return config;
    }
}
