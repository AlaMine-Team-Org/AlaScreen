package day.alacraft.alascreen.api;

/**
 * Every distinct way a call can fail, as one closed set.
 *
 * <p>The first group mirrors the {@code error_code} field the site puts in its
 * error bodies; the rest are produced here, for failures that never reach the
 * site or come back unreadable. They live in the same enum on purpose: the
 * caller's question is always "what do I tell the player", and that has one
 * answer per constant regardless of which side produced it. A single exhaustive
 * {@code switch} covers "wait 40 seconds", "that shot is too big", "check the
 * URL" and "no internet" without an instanceof ladder in front of it.
 *
 * <p>Whether the site was reached at all is not lost in the merge — see
 * {@link #isTransport()} and {@link ApiError#status()}.
 *
 * <p>Unrecognised wire codes become {@link #UNKNOWN} rather than an exception:
 * the site may add a code before the mod knows about it, and an upload must not
 * fail differently because of a string we have not seen before. That tolerance
 * is what lets the site and the mod ship on their own schedules.
 */
public enum ApiErrorCode {

    // ---- Reported by the site, in the error_code field ----

    /** The upload carried no file, or one the site could not read as an image. */
    INVALID_IMAGE("invalid_image"),

    /** A real image, but not PNG, JPEG or WebP. */
    UNSUPPORTED_FORMAT("unsupported_format"),

    /**
     * Too many pixels. Separate from {@link #TOO_LARGE} because the advice is
     * different: a 16K panorama is refused however well it compresses, and the
     * fix is to turn down {@code maxUploadSide}, not to re-encode.
     */
    IMAGE_TOO_LARGE_PX("image_too_large_px"),

    /**
     * The body was refused for size in bytes. Can come from the site or from a
     * proxy in front of it, so it may arrive as a bare 413 with no JSON at all.
     */
    TOO_LARGE("too_large"),

    /** The request was not the shape the endpoint expects. */
    INVALID_REQUEST("invalid_request"),

    /** Wrong or missing token. On delete, that is the shot's own token, not an account one. */
    INVALID_TOKEN("invalid_token"),

    /** The account token is real but not allowed to upload screenshots. */
    INSUFFICIENT_SCOPE("insufficient_scope"),

    /** No such screenshot, or it expired. Retention runs from the last view, not from upload. */
    NOT_FOUND("not_found"),

    /** A moderator took it down. Distinct from {@link #NOT_FOUND} — it existed. */
    REMOVED("removed"),

    /**
     * This exact image was blocked before and will not be accepted again. The
     * site matches on a hash of the content, so re-uploading the same file from
     * a different account changes nothing.
     */
    BLOCKED("blocked"),

    /**
     * Rate limited. {@link ApiError#retryAfter()} usually carries how long to
     * wait. Anonymous uploads share one allowance per IP address — an account
     * token moves the upload onto its own, larger one.
     */
    RATE_LIMITED("rate_limited"),

    // ---- Derived here, from a response that carried no usable code ----

    /** The site answered 5xx. Its problem, not the caller's: worth retrying. */
    SERVER_ERROR("server_error"),

    /** An answer arrived that was not the JSON this API promises — a proxy error page, most likely. */
    MALFORMED_RESPONSE("malformed_response"),

    /** An error we do not recognise. {@link ApiError#rawCode()} keeps what the site actually said. */
    UNKNOWN("unknown"),

    // ---- Produced here, without ever reaching the site ----

    /** The site could not be reached at all: refused, unroutable, or an unresolvable host. */
    OFFLINE("network_offline"),

    /** Reached, but out of time before it answered. */
    TIMEOUT("network_timeout"),

    /** The TLS handshake failed — a certificate or protocol problem, not a missing network. */
    TLS("network_tls"),

    /** A bug on this side. If a player ever sees this, we wrote it wrong. */
    INTERNAL("client_internal");

    private final String wireName;

    ApiErrorCode(String wireName) {
        this.wireName = wireName;
    }

    /** The string form: what the site sends, or a matching name for the client-side codes. */
    public String wireName() {
        return wireName;
    }

    /** The code for a wire string, or {@link #UNKNOWN} for anything unrecognised. */
    public static ApiErrorCode of(String wireName) {
        if (wireName != null && !wireName.isBlank()) {
            for (ApiErrorCode code : values()) {
                if (code.wireName.equals(wireName)) {
                    return code;
                }
            }
        }
        return UNKNOWN;
    }

    /** True when the request never got an answer, so nothing was uploaded or deleted. */
    public boolean isTransport() {
        return this == OFFLINE || this == TIMEOUT || this == TLS;
    }

    /**
     * True when the same request could plausibly succeed later.
     *
     * <p>Deliberately narrow. Re-sending a shot that was refused as too large,
     * or with a token the site rejected, only wastes the user's rate limit.
     */
    public boolean isRetryable() {
        return this == RATE_LIMITED || this == SERVER_ERROR || this == OFFLINE || this == TIMEOUT;
    }

    /**
     * The translation key describing this failure to a player.
     *
     * <p>Built from {@link #wireName()}, not from {@code name()}. The two agree
     * for fourteen of the eighteen constants, which is exactly why the four that
     * disagree went unnoticed: {@code OFFLINE} asked for
     * {@code alascreen.error.offline} while every language file carries
     * {@code network_offline}, so a player with no connection was shown the key
     * itself instead of a sentence — as were {@code TIMEOUT}, {@code TLS} and
     * {@code INTERNAL}. A missing key is not an error the game reports; it is
     * text on screen. {@code LangCatalogTest.keysBuiltFromEnumNamesExistToo}
     * now walks this enum, because a key assembled at runtime is invisible to
     * any scan of the source.
     */
    public String messageKey() {
        return "alascreen.error." + wireName;
    }
}
