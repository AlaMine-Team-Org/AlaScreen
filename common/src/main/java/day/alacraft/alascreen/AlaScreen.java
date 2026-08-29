package day.alacraft.alascreen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants and the mod's logger.
 *
 * <p>Everything in {@code common} is deliberately free of Minecraft types: the
 * work this mod does once it has the pixels — writing a PNG, posting it over
 * HTTP, remembering the delete token — has nothing to do with the game, and
 * keeping it separate means it can be unit-tested in milliseconds and recompiled
 * unchanged into a Fabric jar, a NeoForge jar and whatever comes after.
 */
public final class AlaScreen {

    public static final String MOD_ID = "alascreen";

    public static final String MOD_NAME = "Ala Screen";

    /**
     * Where screenshots are uploaded unless the config points somewhere else.
     *
     * <p>Overridable because a self-hosted instance is a legitimate setup:
     * anyone running their own copy of the site should be able to point the mod
     * at it, and a redirected mod must not still phone home.
     */
    public static final String DEFAULT_API_BASE_URL = "https://alacraft.day/api/v1";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private AlaScreen() {
    }
}
