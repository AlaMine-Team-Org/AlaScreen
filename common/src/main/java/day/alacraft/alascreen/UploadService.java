package day.alacraft.alascreen;

import day.alacraft.alascreen.api.AlaScreenApi;
import day.alacraft.alascreen.api.ApiError;
import day.alacraft.alascreen.api.ApiErrorCode;
import day.alacraft.alascreen.api.ApiException;
import day.alacraft.alascreen.api.Limits;
import day.alacraft.alascreen.api.UploadRequest;
import day.alacraft.alascreen.api.UploadResult;
import day.alacraft.alascreen.history.UploadHistory;
import day.alacraft.alascreen.history.UploadRecord;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Everything that happens between "here are the pixels" and "here is the link".
 *
 * <p>The one place that knows the order of operations, so the loader entrypoints
 * and the command tree stay free of policy. It is Minecraft-free by design: the
 * caller hands over encoded bytes and gets a future back, which is what lets the
 * whole flow be exercised without starting a game.
 */
public final class UploadService {

    private final Config config;
    private final AlaScreenApi api;
    private final UploadHistory history;
    private final String minecraftVersion;
    private final String loader;

    public UploadService(Config config,
                         AlaScreenApi api,
                         UploadHistory history,
                         String minecraftVersion,
                         String loader) {
        this.config = config;
        this.api = api;
        this.history = history;
        this.minecraftVersion = minecraftVersion;
        this.loader = loader;
    }

    /**
     * Ask the site what it accepts, so the first upload of the session does not
     * have to discover it.
     *
     * <p>Failure is swallowed on purpose: not knowing the limits is not an error
     * the player needs to hear about, it just means we fall back to conservative
     * ones and let the site have the final word.
     */
    public void warmUp() {
        api.limits().exceptionally(failure -> {
            AlaScreen.LOGGER.debug("Could not read the upload limits yet ({}).", failure.getMessage());
            return null;
        });
    }

    /** What the site accepts, or conservative defaults while we do not know yet. */
    public Limits limits() {
        return api.cachedLimits().orElseGet(Limits::fallback);
    }

    /**
     * Check a capture before any of it goes on the wire.
     *
     * <p>Local refusal is not an optimisation. Sending eight megabytes over a
     * home connection and being told "too large" on arrival wastes a minute of
     * the player's evening and one of their rate-limit slots, and the answer was
     * knowable before the first byte left.
     *
     * @return the reason it cannot be sent, or empty when it can
     */
    public Optional<ApiErrorCode> reject(long bytes, int width, int height) {
        Limits limits = limits();

        if (limits.rejectsPixels(width, height)) {
            return Optional.of(ApiErrorCode.IMAGE_TOO_LARGE_PX);
        }

        if (limits.rejectsBytes(bytes)) {
            return Optional.of(ApiErrorCode.TOO_LARGE);
        }

        return Optional.empty();
    }

    /**
     * Upload one screenshot and remember it.
     *
     * <p>The history entry is written on the success path only, and before the
     * caller is told: a link the player can see but not delete is worse than a
     * slightly later message.
     */
    public CompletableFuture<UploadResult> upload(byte[] png,
                                                  int width,
                                                  int height,
                                                  String fileName,
                                                  String siteLocale) {
        Optional<ApiErrorCode> refusal = reject(png.length, width, height);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(new ApiException(new ApiError(
                    0, refusal.get(), "", "Refused before sending.", Optional.empty())));
        }

        UploadRequest request = new UploadRequest(
                fileName, png, width, height, siteLocale, minecraftVersion, loader);

        return api.upload(request).thenApply(result -> {
            if (config.persistHistory) {
                history.add(result.id(), result.url(), result.deleteToken(), fileName);
            }

            return result;
        });
    }

    /**
     * Delete an upload by id, or the most recent one when given {@code last}.
     *
     * <p>The record is removed from the local history only after the site has
     * confirmed, so a failed delete leaves the token where the player can try
     * again.
     */
    public CompletableFuture<UploadRecord> delete(String idOrLast) {
        Optional<UploadRecord> target = "last".equalsIgnoreCase(idOrLast)
                ? history.last()
                : history.findById(idOrLast);

        if (target.isEmpty()) {
            return CompletableFuture.failedFuture(new ApiException(new ApiError(
                    0, ApiErrorCode.NOT_FOUND, "", "Nothing in this client's history has that id.",
                    Optional.empty())));
        }

        UploadRecord record = target.get();

        if (!record.hasToken()) {
            return CompletableFuture.failedFuture(new ApiException(new ApiError(
                    0, ApiErrorCode.INVALID_TOKEN, "", "No delete token was stored for that upload.",
                    Optional.empty())));
        }

        return api.delete(record.id(), record.token()).thenApply(ignored -> {
            history.remove(record.id());
            return record;
        });
    }

    public List<UploadRecord> recent(int count) {
        return history.recent(count);
    }

    public Optional<UploadRecord> last() {
        return history.last();
    }

    /** One remembered upload by id, or {@code last} for the most recent. */
    public Optional<UploadRecord> find(String idOrLast) {
        return "last".equalsIgnoreCase(idOrLast) ? history.last() : history.findById(idOrLast);
    }

    public Config config() {
        return config;
    }

    public AlaScreenApi api() {
        return api;
    }
}
