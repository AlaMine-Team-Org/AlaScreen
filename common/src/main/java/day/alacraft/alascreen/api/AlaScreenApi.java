package day.alacraft.alascreen.api;

import com.google.gson.JsonObject;

import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

/**
 * The mod's connection to the AlaCraft screenshot API.
 *
 * <p>Build one per process and keep it: it owns an {@link HttpClient}, a thread
 * pool and the cached limits. Every method returns immediately with a {@link
 * CompletableFuture}; nothing here may ever be called on a thread the game needs
 * back, which for a client mod is stricter than it sounds — the render thread
 * blocking for a two-second upload is a two-second freeze the player sees.
 *
 * <p><b>Nothing is compressed.</b> Ala Logger gzips its uploads because a log is
 * text and compresses tenfold. A PNG is already deflated; gzipping it again
 * costs CPU on the player's machine to make the body very slightly larger.
 *
 * <p><b>Bearer means two different things.</b> On upload it is the player's
 * personal account token from /profile, which attaches the shot to their history
 * and moves them off the shared per-IP rate limit. On delete it is the one-time
 * token that came back with that particular screenshot. They are never
 * interchangeable, so the account token lives on the client and the shot's token
 * is a parameter.
 */
public final class AlaScreenApi implements AutoCloseable {

    /** Long enough for a slow DNS answer, short enough that a dead host is not a hang. */
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Generous on purpose: several megabytes of PNG from a home connection, on a
     * machine that is also rendering a game. Losing the upload to an impatient
     * timeout is worse than waiting.
     */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** How long a shutdown waits for an upload in flight before cutting it off. */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    /**
     * The site's id alphabet with room to spare. Ids reach us from a config file,
     * a chat argument or our own history file, so this is also what keeps
     * {@code ../} out of a URL we build by concatenation.
     */
    private static final Pattern SHOT_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    /**
     * The largest response we are willing to hold in memory.
     *
     * <p>Every response this client reads is small — an upload result, a limits
     * object, an error. But {@code apiBaseUrl} is configurable, so "the server"
     * can be a typo, a captive portal or a proxy handing back something else
     * entirely, and an unbounded read would let any of them take down the game's
     * JVM. One megabyte is orders of magnitude above the real answers and far
     * below anything that hurts.
     */
    private static final int MAX_RESPONSE_BYTES = 1 << 20;

    private final HttpClient http;
    private final ExecutorService executor;
    private final String baseUrl;
    private final String userAgent;
    private final String source;
    private final String apiToken;
    private final Duration requestTimeout;

    /**
     * Holds the in-flight or completed {@link #limits()} call. A failed one is
     * evicted, so a lookup that failed while the site was down is retried rather
     * than remembered for the rest of the session.
     */
    private final AtomicReference<CompletableFuture<Limits>> limitsCache = new AtomicReference<>();

    private AlaScreenApi(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.userAgent = builder.userAgent;
        this.source = builder.source;
        this.apiToken = builder.apiToken;
        this.requestTimeout = builder.requestTimeout;

        // Virtual threads: one per request, no pool to size, and daemon by
        // nature — a forgotten close() cannot keep the JVM from exiting.
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.http = HttpClient.newBuilder()
                .connectTimeout(builder.connectTimeout)
                .executor(executor)
                // Redirects are NOT followed. The JDK would turn a redirected
                // POST into a GET and the screenshot would vanish silently;
                // surfacing the 3xx lets us say "check the base URL" instead.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public static Builder builder(String baseUrl) {
        return new Builder(baseUrl);
    }

    /**
     * The User-Agent the site asks automated clients to send, e.g.
     * {@code AlaScreen/0.1.0 (Minecraft 26.2; fabric)}.
     *
     * <p>This is the mod's entire telemetry, and it is not optional: it is what
     * any HTTP request carries. Naming it here keeps it honest — the store page
     * discloses exactly this string.
     */
    public static String userAgent(String version, String minecraftVersion, String loader) {
        return "AlaScreen/" + headerSafe(version, "dev")
                + " (Minecraft " + headerSafe(minecraftVersion, "unknown")
                + "; " + headerSafe(loader, "unknown") + ")";
    }

    /** The host we talk to, for messages that need to name it. */
    public String host() {
        try {
            return URI.create(baseUrl).getHost();
        } catch (RuntimeException e) {
            return baseUrl;
        }
    }

    /**
     * Upload one screenshot.
     *
     * <p>The multipart body is assembled on the executor, not the caller's
     * thread: for a 4K shot that is several megabytes of array copying, and the
     * caller is usually the render thread.
     */
    public CompletableFuture<UploadResult> upload(UploadRequest request) {
        return CompletableFuture
                .supplyAsync(() -> Multipart.build(request, source), executor)
                .thenCompose(body -> {
                    HttpRequest httpRequest = request(uploadUri(request.siteLocale()))
                            .header("Content-Type", "multipart/form-data; boundary=" + body.boundary())
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body.bytes()))
                            .build();

                    return send(httpRequest, UploadResult::from);
                });
    }

    /**
     * What the site will accept right now.
     *
     * <p>Cached for the session. This is the one request nobody triggers, so it
     * is documented in the README as such: it sends no image and no identifier,
     * and {@code apiBaseUrl} redirects it along with everything else.
     */
    public CompletableFuture<Limits> limits() {
        CompletableFuture<Limits> existing = limitsCache.get();
        if (existing != null) {
            return existing;
        }

        CompletableFuture<Limits> fresh = send(
                request(uri("/screenshots/limits")).GET().build(), Limits::from);

        if (!limitsCache.compareAndSet(null, fresh)) {
            return limitsCache.get();
        }

        // Evict a failure so the next caller asks again rather than inheriting
        // an outage that has since ended.
        fresh.whenComplete((limits, failure) -> {
            if (failure != null) {
                limitsCache.compareAndSet(fresh, null);
            }
        });

        return fresh;
    }

    /**
     * The limits if they have already arrived.
     *
     * <p>Used on the capture path, which cannot wait for the network: if the
     * lookup has not finished we check against {@link Limits#fallback()} and let
     * the site have the final word.
     */
    public Optional<Limits> cachedLimits() {
        CompletableFuture<Limits> cached = limitsCache.get();

        if (cached == null || !cached.isDone() || cached.isCompletedExceptionally()) {
            return Optional.empty();
        }

        return Optional.ofNullable(cached.getNow(null));
    }

    /**
     * Delete a screenshot, proving ownership with the token that came back when
     * it was uploaded.
     */
    public CompletableFuture<Void> delete(String id, String ownerToken) {
        if (id == null || !SHOT_ID.matcher(id).matches()) {
            return CompletableFuture.failedFuture(new ApiException(new ApiError(
                    0, ApiErrorCode.INVALID_REQUEST, "", "That is not a screenshot id.", Optional.empty())));
        }

        HttpRequest request = request(uri("/screenshots/" + id))
                .header("Authorization", "Bearer " + headerSafe(ownerToken, ""))
                .DELETE()
                .build();

        return send(request, json -> null);
    }

    @Override
    public void close() {
        executor.shutdown();

        try {
            // Give an upload in flight its few seconds rather than killing it
            // mid-body: the player pressed the key, the bytes are on the wire,
            // and the difference is whether their link exists.
            if (!executor.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ---- request plumbing ----

    private HttpRequest.Builder request(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json");

        if (apiToken != null && !apiToken.isBlank()) {
            builder.header("Authorization", "Bearer " + headerSafe(apiToken, ""));
        }

        return builder;
    }

    private URI uploadUri(String siteLocale) {
        return siteLocale == null || siteLocale.isEmpty()
                ? uri("/screenshots")
                : uri("/screenshots?lang=" + URLEncoder.encode(siteLocale, StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private static HttpResponse.BodyHandler<String> boundedString() {
        return info -> HttpResponse.BodySubscribers.limiting(
                HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), MAX_RESPONSE_BYTES);
    }

    private <T> CompletableFuture<T> send(HttpRequest request, Function<JsonObject, T> mapper) {
        return http.sendAsync(request, boundedString())
                .handle((response, failure) -> {
                    if (failure != null) {
                        Throwable cause = unwrap(failure);

                        throw cause instanceof ApiException api
                                ? api
                                : new ApiException(transportError(cause), cause);
                    }

                    return mapper.apply(body(response));
                });
    }

    /**
     * The JSON of a successful response, or an {@link ApiException} describing
     * why there is none.
     */
    private static JsonObject body(HttpResponse<String> response) {
        int status = response.statusCode();
        JsonObject json = Json.parseObject(response.body());

        if (status >= 200 && status < 300) {
            if (json == null) {
                // A 200 that is not JSON is a proxy or a captive portal, not the
                // API. Saying so beats a NullPointerException three frames later.
                throw new ApiException(new ApiError(
                        status,
                        ApiErrorCode.MALFORMED_RESPONSE,
                        "",
                        "The server answered HTTP " + status + " with something that is not JSON.",
                        Optional.empty()));
            }

            // The contract puts failures on non-2xx statuses, so this should be
            // unreachable — but a body that says it failed is believed over a
            // status that says it did not.
            if (json.has("success") && !Json.boolValue(json, "success", true)) {
                throw new ApiException(error(response, json));
            }

            return json;
        }

        throw new ApiException(error(response, json));
    }

    /** An error response, whether or not it was the JSON we expect. */
    private static ApiError error(HttpResponse<String> response, JsonObject json) {
        int status = response.statusCode();
        String rawCode = json == null ? "" : Json.string(json, "error_code");
        String message = json == null ? "" : Json.string(json, "error");

        // An error can arrive from in front of the application — nginx refusing
        // an oversized body, Cloudflare rate limiting, a gateway timeout — and
        // those answer with HTML. The status is then all we have, and it is
        // enough to reach the right case.
        ApiErrorCode code = rawCode.isEmpty() ? codeForStatus(status) : ApiErrorCode.of(rawCode);

        return new ApiError(
                status,
                code,
                rawCode,
                message.isEmpty() ? fallbackMessage(status) : message,
                retryAfter(response));
    }

    private static ApiErrorCode codeForStatus(int status) {
        return switch (status) {
            case 401 -> ApiErrorCode.INVALID_TOKEN;
            case 403 -> ApiErrorCode.INSUFFICIENT_SCOPE;
            case 404 -> ApiErrorCode.NOT_FOUND;
            case 410 -> ApiErrorCode.REMOVED;
            case 413 -> ApiErrorCode.TOO_LARGE;
            case 415 -> ApiErrorCode.UNSUPPORTED_FORMAT;
            case 422 -> ApiErrorCode.INVALID_IMAGE;
            case 429 -> ApiErrorCode.RATE_LIMITED;
            default -> status >= 500 ? ApiErrorCode.SERVER_ERROR : ApiErrorCode.UNKNOWN;
        };
    }

    private static String fallbackMessage(int status) {
        if (status >= 300 && status < 400) {
            return "The server redirected the request instead of answering it (HTTP " + status
                    + "). Check the API base URL — an http:// address for a site that serves https:// does this.";
        }

        return "The server answered HTTP " + status + " without a readable error message.";
    }

    /**
     * How long the site wants us to wait.
     *
     * <p>Only the delta-seconds form is read. The HTTP-date form is legal but
     * neither Laravel's throttler nor anything in front of it sends one, and
     * guessing wrong about a clock skew would be worse than saying "later".
     */
    private static Optional<Duration> retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .map(String::trim)
                .flatMap(value -> {
                    try {
                        long seconds = Long.parseLong(value);

                        return seconds >= 0 ? Optional.of(Duration.ofSeconds(seconds)) : Optional.empty();
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                });
    }

    /**
     * A failure that is not the site's answer but the absence of one.
     *
     * <p>Kept apart from HTTP errors because the advice differs completely: "no
     * route to alacraft.day" is the player's network, "429" is ours, and telling
     * someone to check their firewall because we were rate limited wastes their
     * evening.
     */
    private static ApiError transportError(Throwable cause) {
        ApiErrorCode code = switch (cause) {
            // Subclass of HttpTimeoutException, so it has to come first. Running
            // out of time while still trying to connect is a reachability
            // problem, not a slow server.
            case HttpConnectTimeoutException ignored -> ApiErrorCode.OFFLINE;
            case HttpTimeoutException ignored -> ApiErrorCode.TIMEOUT;
            case UnknownHostException ignored -> ApiErrorCode.OFFLINE;
            case ConnectException ignored -> ApiErrorCode.OFFLINE;
            case SSLException ignored -> ApiErrorCode.TLS;
            default -> ApiErrorCode.OFFLINE;
        };

        return new ApiError(0, code, "", describe(cause), Optional.empty());
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();

        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;

        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }

        return current;
    }

    /**
     * Header values must be Latin-1 and free of control characters; a version
     * string or a loader name that is neither would make the JDK throw while
     * building the request, turning a cosmetic problem into a failed upload.
     */
    private static String headerSafe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        String cleaned = value.replaceAll("[^\\x20-\\x7E]", "").trim();

        return cleaned.isEmpty() ? fallback : cleaned;
    }

    public static final class Builder {

        private final String baseUrl;
        private String userAgent = AlaScreenApi.userAgent("dev", "unknown", "unknown");
        private String source = "";
        private String apiToken = "";
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

        private Builder(String baseUrl) {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("The API base URL cannot be empty.");
            }

            String trimmed = baseUrl.trim();
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }

            String scheme = trimmed.toLowerCase(Locale.ROOT);
            if (!scheme.startsWith("http://") && !scheme.startsWith("https://")) {
                throw new IllegalArgumentException("The API base URL must start with http:// or https://.");
            }

            // Fail here rather than at the first upload: a malformed URL in the
            // config should be reported while the game is starting, not when the
            // player presses the key.
            URI.create(trimmed);

            this.baseUrl = trimmed;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        /** The {@code source} the site records and shows as a badge. */
        public Builder source(String source) {
            this.source = source == null ? "" : source;
            return this;
        }

        public Builder apiToken(String apiToken) {
            this.apiToken = apiToken == null ? "" : apiToken.trim();
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public AlaScreenApi build() {
            return new AlaScreenApi(this);
        }
    }
}
