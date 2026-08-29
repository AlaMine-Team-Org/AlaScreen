package day.alacraft.alascreen.mc;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.net.URI;
import java.time.Duration;

/**
 * Turns the mod's messages into chat components.
 *
 * <p>Kept apart from everything else because it is the only place that knows
 * what the mod looks like. One uncoloured line for success and one red line for
 * every failure would leave a player unable to tell "no network" from "shot too
 * large" without opening the game log; here each state has its own shape.
 *
 * <p><b>Everything is {@code translatable}, not a resolved string.</b> Ala Logger
 * resolves text itself because it runs on a server and talks to players who do
 * not have it installed. This mod is client-only: whoever reads the line has the
 * mod, so the game resolves the key against {@code assets/alascreen/lang/} and
 * the player's own resource packs get a say too.
 */
public final class ChatFormat {

    /**
     * The mod's own colour, on the prefix only.
     *
     * <p>Green rather than the aqua used for links: the two need to be different
     * colours or the eye cannot separate "who is talking" from "what to click".
     */
    private static final ChatFormatting BRAND = ChatFormatting.GREEN;

    /** Body text. Grey, not white: softer against the world behind the chat. */
    private static final ChatFormatting BODY = ChatFormatting.GRAY;

    private ChatFormat() {
    }

    /**
     * Prefix plus body, each with its own colour.
     *
     * <p>Built on an empty, unstyled parent on purpose. A component's children
     * inherit its style, so appending to a coloured prefix — the obvious way to
     * write this — silently paints the whole line the prefix's colour.
     */
    public static MutableComponent prefixed(MutableComponent body) {
        return Component.empty()
                .append(Component.translatable("alascreen.prefix").withStyle(BRAND))
                .append(body);
    }

    public static MutableComponent info(String key, Object... args) {
        return prefixed(Component.translatable(key, args).withStyle(BODY));
    }

    public static MutableComponent error(String key, Object... args) {
        return prefixed(Component.translatable(key, args).withStyle(ChatFormatting.RED));
    }

    public static MutableComponent detail(String text) {
        return Component.literal(text).withStyle(ChatFormatting.DARK_GRAY);
    }

    /**
     * The uploaded screenshot's address, as a clickable link.
     *
     * <p>Shown without the scheme: the URL is the payload of the whole feature
     * and a player is going to read it aloud, paste it into Discord or type it
     * on a phone. {@code alacraft.day/en/screenshots/aB3xY9kM} survives all
     * three better than the same string with eight extra characters in front.
     */
    public static MutableComponent link(String url) {
        String shown = url.replaceFirst("^https?://", "");

        return Component.literal(shown).withStyle(style -> style
                // Aqua and underlined: the one thing on the line that is meant to
                // be clicked should not share a colour with anything that is not.
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(url))));
    }

    /**
     * A bracketed action, e.g. {@code [delete]}.
     *
     * @param command the client command it runs, without the leading slash
     */
    public static MutableComponent button(String labelKey, String hoverKey, String command) {
        return Component.translatable(labelKey).withStyle(style -> style
                // Dimmer than the body text: the buttons are always there, so they
                // should sit behind the sentence rather than compete with it.
                .withColor(ChatFormatting.DARK_AQUA)
                .withClickEvent(new ClickEvent.RunCommand("/" + command))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable(hoverKey))));
    }

    /** A button that opens a URL rather than running a command. */
    public static MutableComponent urlButton(String labelKey, String hoverKey, String url) {
        return Component.translatable(labelKey).withStyle(style -> style
                .withColor(ChatFormatting.DARK_AQUA)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable(hoverKey))));
    }

    /**
     * A button that copies text to the clipboard.
     *
     * <p>Vanilla has a click action for exactly this, which is better than
     * asking the player to select a chat line by hand.
     */
    public static MutableComponent copyButton(String labelKey, String hoverKey, String value) {
        return Component.translatable(labelKey).withStyle(style -> style
                .withColor(ChatFormatting.DARK_AQUA)
                .withClickEvent(new ClickEvent.CopyToClipboard(value))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable(hoverKey))));
    }

    public static MutableComponent space() {
        return Component.literal(" ");
    }

    /** Human-readable file size, because "2.4 MB" means more than "2516582". */
    public static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return Math.round(bytes / 1024.0) + " KB";
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * How long to wait, in the units a person would use.
     *
     * <p>"Try again in 154 s" is worse than "try again in 3 min" — the point of
     * showing the number at all is that the player decides whether to wait.
     */
    public static Component waitFor(Duration duration) {
        long seconds = Math.max(1, duration.toSeconds());

        return seconds < 90
                ? Component.translatable("alascreen.time.seconds", seconds)
                : Component.translatable("alascreen.time.minutes", Math.round(seconds / 60.0));
    }
}
