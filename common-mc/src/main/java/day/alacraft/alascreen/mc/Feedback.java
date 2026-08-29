package day.alacraft.alascreen.mc;

import day.alacraft.alascreen.AlaScreen;
import day.alacraft.alascreen.api.ApiError;
import day.alacraft.alascreen.api.ApiErrorCode;
import day.alacraft.alascreen.api.ApiException;
import day.alacraft.alascreen.api.Limits;
import day.alacraft.alascreen.api.UploadResult;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * What the player is told, for every way this can end.
 *
 * <p>The reason this class exists as a class: a single "upload failed" line is
 * the difference between a tool people trust and one they uninstall. Rate
 * limited, offline, too large and "a moderator removed it" need four different
 * sentences because they need four different responses from the player, and
 * three of them are not the player's fault at all.
 */
public final class Feedback {

    private Feedback() {
    }

    /**
     * The success line: one sentence, the link, and the three things you might
     * want to do with it.
     *
     * <p>Everything on one line on purpose. A four-line block for a successful
     * screenshot is spam, and the chat is also the game.
     */
    public static Component uploaded(UploadResult result) {
        MutableComponent line = ChatFormat.info("alascreen.chat.uploaded")
                .append(ChatFormat.link(result.url()))
                .append(ChatFormat.space())
                .append(ChatFormat.urlButton("alascreen.button.open",
                        "alascreen.button.open.hover", result.url()))
                .append(ChatFormat.space())
                .append(ChatFormat.copyButton("alascreen.button.copy",
                        "alascreen.button.copy.hover", result.url()));

        // The delete button is only offered when a token came back — a button
        // that cannot work is worse than no button.
        if (result.deleteTokenIfPresent().isPresent()) {
            line.append(ChatFormat.space())
                    .append(ChatFormat.button("alascreen.button.delete",
                            "alascreen.button.delete.hover", "alascreen delete " + result.id()));
        }

        return line;
    }

    /**
     * Turn any failure into one red line the player can act on.
     *
     * @param host    the site we were talking to, named in the network messages
     *                so someone running their own instance is not told to check
     *                their connection to a domain they never used
     * @param limits  what the site accepts, for the size messages
     * @param width   the capture's dimensions, for the pixel message
     */
    public static Component failed(Throwable failure, String host, Limits limits, int width, int height) {
        Throwable cause = unwrap(failure);

        ApiError error = cause instanceof ApiException api
                ? api.error()
                : null;

        if (error == null) {
            // Not an API failure at all — a bug on our side. Say so plainly
            // rather than blaming the network, and LOG it: this message tells
            // the player to check the game log, so something had better be
            // there. The first version did not, and an ordinary "no such
            // endpoint" spent an evening looking like an internal defect.
            AlaScreen.LOGGER.error("Screenshot upload failed with an unexpected error.", cause);

            return ChatFormat.error("alascreen.error.client_internal");
        }

        ApiErrorCode code = error.code();

        return switch (code) {
            case RATE_LIMITED -> ChatFormat.error("alascreen.error.rate_limited",
                    error.retryAfter().map(ChatFormat::waitFor)
                            .orElseGet(() -> Component.translatable("alascreen.time.minutes", 1)));

            case TOO_LARGE -> ChatFormat.error("alascreen.error.too_large",
                    ChatFormat.size(estimatedBytes(width, height)),
                    ChatFormat.size(limits.maxBytes()));

            case IMAGE_TOO_LARGE_PX -> ChatFormat.error("alascreen.error.image_too_large_px",
                    width, height);

            case OFFLINE, TIMEOUT, TLS -> ChatFormat.error(code.messageKey(), host);

            // Everything else has a self-contained sentence keyed by the code,
            // so the mapping is the enum itself rather than a second switch that
            // can fall out of step with it.
            case UNKNOWN -> ChatFormat.error("alascreen.error.unknown", error.message());

            default -> ChatFormat.error(code.messageKey());
        };
    }

    /**
     * A stand-in for the uploaded size when the site refused the body before we
     * learned it, which is what a bare 413 from a proxy looks like.
     *
     * <p>Four bytes a pixel is the uncompressed upper bound; a PNG is always
     * smaller, so this over-reports. It is only used in a message that already
     * says "too large", where an approximate number beats no number.
     */
    private static long estimatedBytes(int width, int height) {
        return (long) width * height * 4L;
    }

    /**
     * Reach the failure the caller actually cares about.
     *
     * <p>Anything thrown inside a {@link java.util.concurrent.CompletableFuture}
     * chain arrives here wrapped in a {@link CompletionException}. Without this,
     * the {@code instanceof ApiException} above never matched: every real
     * failure — a 404, a rate limit, an offline network — was reported as an
     * internal error, with nothing in the log, because the exception the site
     * had carefully described was sitting one level down inside a wrapper.
     */
    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;

        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }

        return current;
    }
}
