package day.alacraft.alascreen.mc;

import day.alacraft.alascreen.AlaScreen;
import day.alacraft.alascreen.Config;
import day.alacraft.alascreen.UploadService;
import day.alacraft.alascreen.clipboard.CopyOutcome;
import day.alacraft.alascreen.clipboard.ImageClipboard;
import day.alacraft.alascreen.history.UploadRecord;
import day.alacraft.alascreen.i18n.SiteLocale;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The flow, from key press to chat line.
 *
 * <p>Loader-neutral on purpose: Fabric and NeoForge differ in how a key binding
 * and a client command are registered, and in nothing else. Everything a player
 * experiences lives here, so the second loader is an entrypoint rather than a
 * second implementation.
 */
public final class ShareController {

    private final Config config;
    private final UploadService service;
    private final Path configFile;
    private final Path screenshotsDirectory;

    /**
     * One capture at a time.
     *
     * <p>Holding the key down, or pressing it while the previous shot is still
     * uploading, would otherwise start a second capture, and on a slow
     * connection a player could queue a dozen without noticing until the rate
     * limiter answered for us.
     */
    private final AtomicBoolean busy = new AtomicBoolean();

    /**
     * One clipboard copy at a time.
     *
     * <p>Separate from {@link #busy}: copying an old screenshot has nothing to do
     * with taking a new one, and sharing a flag would make each refuse the other
     * for no reason a player could guess.
     */
    private final AtomicBoolean copying = new AtomicBoolean();

    /**
     * The share key, once a loader has registered it.
     *
     * <p>Only for naming it: the plaque has to say which key shares the shot, and
     * "press the Ala Screen key" is not an answer when the player has rebound it.
     * Null until an entrypoint hands it over, and the plaque says so rather than
     * guessing.
     */
    private KeyMapping shareKey;

    public ShareController(Config config,
                           UploadService service,
                           Path configFile,
                           Path screenshotsDirectory) {
        this.config = config;
        this.service = service;
        this.configFile = configFile;
        this.screenshotsDirectory = screenshotsDirectory;

        // Registered here rather than from each entrypoint: this constructor runs
        // on both loaders by construction, so the plaque cannot exist on one and
        // be forgotten on the other.
        ScreenshotHook.whenSaved(this::offerSaved);
    }

    /** Tell the controller which key shares a shot, so the plaque can name it. */
    public void useShareKey(KeyMapping key) {
        this.shareKey = key;
    }

    /**
     * What the share key does, which depends on whether a plaque is up.
     *
     * <p>While the plaque is on screen the key shares the shot it is showing;
     * otherwise it takes a new one. That is not an overloaded key for the sake of
     * it — it is the only way a plaque can offer an action at all. With no screen
     * open the cursor is captured by the game and has no screen position, so
     * mouse input never reaches a toast; a key press is the only input that
     * arrives exactly while the plaque is visible.
     *
     * <p>It also settles the oldest problem with screenshot overlays: the plaque
     * is taken down before any new capture, so it cannot be photographed by the
     * next shot.
     */
    public void onShareKey(Minecraft minecraft) {
        Optional<String> offered = SharePrompt.armedFileName();

        if (offered.isPresent()) {
            SharePrompt.dismiss();
            uploadExisting(minecraft, offered.get());
            return;
        }

        SharePrompt.dismiss();
        captureAndShare(minecraft);
    }

    /**
     * Housekeeping for the plaque. Call once per client tick.
     *
     * <p>The picture behind the plaque is native memory and a GPU texture, and
     * the toast cannot free them itself: leaving a world throws every toast away
     * without telling it. So the release happens here, off the render path.
     */
    public void clientTick(Minecraft minecraft) {
        SharePrompt.tick(minecraft);
    }

    /**
     * Vanilla just saved a screenshot: offer it on a plaque.
     *
     * <p>Reached from the F2 path only, which is the one that needs it. Our own
     * key either opens the confirmation screen — which already shows the frame,
     * far larger — or uploads straight away, and a plaque about a shot that is
     * already on its way would say nothing useful.
     *
     * <p>Runs on vanilla's io thread, so nothing here may touch the game
     * directly; {@link SharePrompt#show} hops to the client thread itself.
     */
    private void offerSaved(java.io.File file) {
        if (!config.toastAfterCapture) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null) {
            return;
        }

        // F2 works in the main menu too, and a plaque there would be a lie: the
        // key it names is only polled while no screen is open, so in a menu the
        // instruction cannot be followed. The chat line keeps its share button.
        if (minecraft.level == null) {
            return;
        }

        Component hint = shareKey == null || shareKey.isUnbound()
                ? Component.translatable("alascreen.toast.hint_unbound")
                : Component.translatable("alascreen.toast.hint", shareKey.getTranslatedKeyMessage());

        SharePrompt.show(minecraft, file.toPath(),
                Component.translatable("alascreen.toast.saved"), hint);
    }

    /**
     * Capture the frame and either ask or upload, depending on the config.
     *
     * <p>Call on the render thread.
     */
    public void captureAndShare(Minecraft minecraft) {
        if (!busy.compareAndSet(false, true)) {
            return;
        }

        tell(minecraft, ChatFormat.info("alascreen.chat.capturing"));

        Optional<Path> saveTo = config.saveLocalCopy ? Optional.of(screenshotsDirectory) : Optional.empty();

        ScreenCapture.capture(minecraft, saveTo, runnable -> new Thread(runnable, "AlaScreen encode").start())
                .whenComplete((shot, failure) -> minecraft.execute(() -> {
                    if (failure != null) {
                        busy.set(false);
                        AlaScreen.LOGGER.error("Capture failed.", failure);
                        tell(minecraft, ChatFormat.error("alascreen.error.capture_failed"));
                        return;
                    }

                    onCaptured(minecraft, shot);
                }));
    }

    /** Runs on the render thread: from here on we may touch screens and chat. */
    private void onCaptured(Minecraft minecraft, CapturedShot original) {
        original.savedTo().ifPresent(path ->
                tell(minecraft, ChatFormat.info("alascreen.chat.saved_local", path.getFileName().toString())));

        proceed(minecraft, original);
    }

    /**
     * Scale if needed, ask if configured, then upload.
     *
     * <p>Shared by both entry points — a fresh capture and an existing file
     * offered by the F2 button — so the confirmation, the size handling and the
     * messages cannot drift apart between them.
     */
    private void proceed(Minecraft minecraft, CapturedShot original) {
        // A 21:9 or 4K monitor produces a PNG well past any sane upload limit,
        // every single time. Refusing those would mean the mod works on small
        // screens and not on good ones, so the upload is scaled to fit instead —
        // the file on disk keeps its full resolution either way.
        CapturedShot shot = ScreenCapture.fitToLimit(
                original,
                service.limits().maxBytes(),
                service.limits().maxPixels(),
                screenshotsDirectory.resolveSibling(".alascreen-tmp"));

        if (shot != original) {
            tell(minecraft, ChatFormat.info("alascreen.chat.resized",
                    shot.width(), shot.height(), ChatFormat.size(shot.bytes())));
        }

        // Refuse locally what the site would refuse anyway, before the player is
        // asked a question whose answer cannot be honoured.
        Optional<day.alacraft.alascreen.api.ApiErrorCode> refusal =
                service.reject(shot.bytes(), shot.width(), shot.height());

        if (refusal.isPresent()) {
            shot.close();
            busy.set(false);
            tell(minecraft, Feedback.failed(
                    new day.alacraft.alascreen.api.ApiException(new day.alacraft.alascreen.api.ApiError(
                            0, refusal.get(), "", "Refused before sending.", Optional.empty())),
                    service.api().host(), service.limits(), shot.width(), shot.height()));
            return;
        }

        if (!config.confirmBeforeUpload) {
            shot.close();
            upload(minecraft, shot);
            return;
        }

        minecraft.setScreenAndShow(new ConfirmUploadScreen(
                shot,
                confirmed -> upload(minecraft, confirmed),
                kept -> saveOnly(minecraft, kept),
                () -> busy.set(false),
                this::rememberDoNotAsk));
    }

    /**
     * The player chose to keep the shot without sharing it.
     *
     * <p>When {@code saveLocalCopy} is on there is already a file and this only
     * has to say so. When it is off there is not: the capture lives in memory
     * and the encode used a temporary file that has already been deleted, so
     * the button has to write one now or it would be a button that does nothing.
     */
    private void saveOnly(Minecraft minecraft, CapturedShot shot) {
        busy.set(false);

        if (shot.savedTo().isPresent()) {
            return;
        }

        try {
            java.nio.file.Files.createDirectories(screenshotsDirectory);
            Path target = screenshotsDirectory.resolve(shot.fileName());
            java.nio.file.Files.write(target, shot.png());

            tell(minecraft, ChatFormat.info("alascreen.chat.saved_local",
                    target.getFileName().toString()));
        } catch (IOException e) {
            AlaScreen.LOGGER.warn("Could not save the screenshot ({}).", e.getMessage());
            tell(minecraft, ChatFormat.error("alascreen.error.capture_failed"));
        }
    }

    /**
     * The player ticked "do not ask again", so persist it.
     *
     * <p>Written to disk immediately rather than at shutdown: a setting that
     * silently forgets itself when the game crashes is worse than one that never
     * existed, and this particular one changes what leaves the machine.
     */
    private void rememberDoNotAsk(boolean remember) {
        if (!remember) {
            return;
        }

        config.confirmBeforeUpload = false;

        try {
            config.save(configFile);
        } catch (IOException e) {
            AlaScreen.LOGGER.warn("Could not save the config after 'do not ask again' ({}).", e.getMessage());
        }
    }

    private void upload(Minecraft minecraft, CapturedShot shot) {
        tell(minecraft, ChatFormat.info("alascreen.chat.uploading",
                shot.fileName(), ChatFormat.size(shot.bytes())));

        String locale = SiteLocale.of(minecraft.options.languageCode);

        service.upload(shot.png(), shot.width(), shot.height(), shot.fileName(), locale)
                .whenComplete((result, failure) -> minecraft.execute(() -> {
                    busy.set(false);

                    if (failure != null) {
                        tell(minecraft, Feedback.failed(failure, service.api().host(),
                                service.limits(), shot.width(), shot.height()));
                        return;
                    }

                    // Two conditions, not one. The picture is read off the disk,
                    // so a local file has to exist — and it is found through the
                    // upload history, so the history has to be kept. With
                    // `persistHistory` off the file is right there and the id
                    // leads nowhere, which is the one shape of this button that
                    // looks like it works and never can.
                    boolean canCopyImage = shot.savedTo().isPresent() && config.persistHistory;

                    tell(minecraft, Feedback.uploaded(result, canCopyImage));

                    if (config.copyLinkToClipboard) {
                        minecraft.keyboardHandler.setClipboard(result.url());
                        tell(minecraft, ChatFormat.info("alascreen.chat.copied"));
                    }
                }));
    }

    /**
     * Upload a screenshot already on disk, named by the F2 message's button.
     *
     * <p>The file is read and decoded off the render thread: a 4K PNG is tens of
     * megabytes decompressed, and doing that inline would drop frames at the
     * exact moment the player is looking at the result of pressing a key.
     */
    public void uploadExisting(Minecraft minecraft, String fileName) {
        if (!busy.compareAndSet(false, true)) {
            return;
        }

        Optional<Path> resolved = insideScreenshots(fileName);

        if (resolved.isEmpty()) {
            busy.set(false);
            tell(minecraft, ChatFormat.error("alascreen.error.not_found"));
            return;
        }

        Path path = resolved.get();

        CompletableFuture
                .supplyAsync(() -> read(path, fileName), runnable ->
                        new Thread(runnable, "AlaScreen read").start())
                .whenComplete((shot, failure) -> minecraft.execute(() -> {
                    if (failure != null) {
                        busy.set(false);
                        AlaScreen.LOGGER.warn("Could not read {} for upload.", path, failure);
                        tell(minecraft, ChatFormat.error("alascreen.error.not_found"));
                        return;
                    }

                    proceed(minecraft, shot);
                }));
    }

    /**
     * Upload the newest file in the screenshots folder.
     *
     * <p>The fallback for a bare {@code /alascreen upload}, and the reason it
     * exists is a real failure: the share button's argument went missing between
     * the chat line and the parser, and the command arrived as "upload" with
     * nothing after it. Brigadier answered "unknown or incomplete command",
     * which tells the player nothing and looks like the mod is broken.
     *
     * <p>Newest-file is the right guess precisely here — the button is attached
     * to the message about a screenshot taken seconds ago — and it is a guess
     * only when the argument is absent. With a name, the name wins.
     */
    public void uploadLatest(Minecraft minecraft) {
        try (var files = Files.list(screenshotsDirectory)) {
            Optional<Path> newest = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                    .max(java.util.Comparator.comparingLong(path -> path.toFile().lastModified()));

            if (newest.isEmpty()) {
                tell(minecraft, ChatFormat.info("alascreen.chat.empty_history"));
                return;
            }

            uploadExisting(minecraft, newest.get().getFileName().toString());
        } catch (IOException e) {
            AlaScreen.LOGGER.warn("Could not list {} ({}).", screenshotsDirectory, e.getMessage());
            tell(minecraft, ChatFormat.error("alascreen.error.not_found"));
        }
    }

    /**
     * Resolve a file name against the screenshots folder, refusing to leave it.
     *
     * <p>The name arrives through a chat command, so it is caller-controlled:
     * without the check, {@code "../../.ssh/id_rsa"} would be a valid screenshot
     * to share — or, now, to put on the clipboard. One copy of the rule, used by
     * both paths, because a second copy is the one that eventually drifts.
     */
    private Optional<Path> insideScreenshots(String fileName) {
        try {
            Path path = screenshotsDirectory.resolve(fileName).normalize();

            return path.startsWith(screenshotsDirectory.normalize()) ? Optional.of(path) : Optional.empty();
        } catch (java.nio.file.InvalidPathException impossibleName) {
            // Windows refuses ':', '?' and '*' in a name, and resolve() answers
            // with an exception rather than a value. The name reaches here from
            // a chat command, so `/alascreen upload "a:b.png"` would otherwise
            // throw out of the command handler instead of printing one red line.
            return Optional.empty();
        }
    }

    private static CapturedShot read(Path path, String fileName) {
        try {
            byte[] png = Files.readAllBytes(path);
            NativeImage image = NativeImage.read(png);

            return new CapturedShot(png, image.getWidth(), image.getHeight(),
                    fileName, Optional.of(path), image);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + path + ": " + e.getMessage(), e);
        }
    }

    // ---- commands ----

    public void list(Minecraft minecraft) {
        List<UploadRecord> recent = service.recent(10);

        if (recent.isEmpty()) {
            tell(minecraft, ChatFormat.info("alascreen.chat.empty_history"));
            return;
        }

        tell(minecraft, ChatFormat.info("alascreen.chat.list_header"));

        for (UploadRecord record : recent) {
            tell(minecraft, Component.empty()
                    .append(ChatFormat.detail(record.id()))
                    .append(ChatFormat.space())
                    .append(ChatFormat.link(record.url()))
                    .append(ChatFormat.space())
                    .append(ChatFormat.button("alascreen.button.delete",
                            "alascreen.button.delete.hover", "alascreen delete " + record.id())));
        }
    }

    /**
     * Put the picture itself on the clipboard, not its address.
     *
     * <p>Reached from the {@code [copy image]} button and from
     * {@code /alascreen copyimage}. The picture comes off the disk: once an
     * upload has finished the mod holds no pixels, and the history remembers a
     * file name rather than an image.
     *
     * <p>The copy runs off the render thread. The system clipboard can block
     * while another application hands over ownership, and on macOS this spawns a
     * process; either would be a visible stall at the exact moment the player
     * pressed a button.
     */
    public void copyImage(Minecraft minecraft, String idOrLast) {
        // A chat button is trivially double-clicked, and this path says nothing
        // while it works, so a second click is what a player naturally does.
        // Without the guard that is two threads, two full PNG decodes — tens of
        // megabytes each for a 4K shot — and a race for clipboard ownership in
        // which the winner is whichever finished last, not whichever was asked
        // last. Its own flag rather than `busy`: copying has nothing to do with
        // capturing, and blocking one on the other would be a different lie.
        if (!copying.compareAndSet(false, true)) {
            return;
        }

        Optional<UploadRecord> target = service.find(idOrLast);

        if (target.isEmpty()) {
            copying.set(false);

            // Nothing uploaded from this client yet is not the same as "no such
            // id", and `list` and `upload` already say so in those words.
            tell(minecraft, service.recent(1).isEmpty()
                    ? ChatFormat.info("alascreen.chat.empty_history")
                    : ChatFormat.error("alascreen.error.not_found"));
            return;
        }

        Optional<Path> path = insideScreenshots(target.get().fileName());

        // The file existed when the button was drawn. A week later it may not:
        // the screenshots folder belongs to the player, not to the mod.
        if (path.isEmpty() || !Files.isRegularFile(path.get())) {
            copying.set(false);
            tell(minecraft, ChatFormat.error(CopyOutcome.FILE_MISSING.messageKey()));
            return;
        }

        CompletableFuture
                .supplyAsync(() -> ImageClipboard.copy(path.get()), runnable ->
                        new Thread(runnable, "AlaScreen clipboard").start())
                .whenComplete((outcome, failure) -> minecraft.execute(() -> {
                    copying.set(false);

                    if (failure != null) {
                        AlaScreen.LOGGER.warn("Copying {} to the clipboard failed.", path.get(), failure);
                        tell(minecraft, ChatFormat.error(CopyOutcome.FAILED.messageKey()));
                        return;
                    }

                    tell(minecraft, outcome.succeeded()
                            ? ChatFormat.info(outcome.messageKey())
                            : ChatFormat.error(outcome.messageKey()));
                }));
    }

    /**
     * Ask before deleting, because deleting cannot be undone.
     *
     * <p>The button sits in a chat line the player may scroll past, click by
     * accident, or reach a week later when they no longer remember which
     * screenshot it was. One extra click costs a second; a wrong one costs a
     * link that other people may already have shared.
     */
    public void askDelete(Minecraft minecraft, String idOrLast) {
        Optional<UploadRecord> target = service.find(idOrLast);

        if (target.isEmpty()) {
            tell(minecraft, ChatFormat.error("alascreen.error.not_found"));
            return;
        }

        String id = target.get().id();

        tell(minecraft, ChatFormat.info("alascreen.chat.delete_confirm", id)
                .append(ChatFormat.space())
                .append(ChatFormat.button("alascreen.button.confirm_delete",
                        "alascreen.button.confirm_delete.hover", "alascreen delete " + id + " confirm"))
                .append(ChatFormat.space())
                .append(ChatFormat.button("alascreen.button.cancel",
                        "alascreen.button.cancel.hover", "alascreen delete " + id + " cancel")));
    }

    public void cancelDelete(Minecraft minecraft) {
        tell(minecraft, ChatFormat.info("alascreen.chat.delete_cancelled"));
    }

    public void delete(Minecraft minecraft, String idOrLast) {
        service.delete(idOrLast).whenComplete((record, failure) -> minecraft.execute(() -> {
            if (failure != null) {
                tell(minecraft, Feedback.failed(failure, service.api().host(), service.limits(), 0, 0));
                return;
            }

            tell(minecraft, ChatFormat.info("alascreen.chat.deleted", record.id()));
        }));
    }

    public void help(Minecraft minecraft) {
        tell(minecraft, ChatFormat.info("alascreen.command.help_header"));

        helpLine(minecraft, "alascreen", "alascreen.command.help_last");
        helpLine(minecraft, "alascreen list", "alascreen.command.help_list");
        helpLine(minecraft, "alascreen copyimage <id|last>", "alascreen.command.help_copy_image");
        helpLine(minecraft, "alascreen delete <id|last>", "alascreen.command.help_delete");
        helpLine(minecraft, "alascreen help", "alascreen.command.help_help");
    }

    private void helpLine(Minecraft minecraft, String usage, String descriptionKey) {
        tell(minecraft, Component.empty()
                .append(ChatFormat.detail("/" + usage + "  "))
                .append(Component.translatable(descriptionKey)
                        .withStyle(net.minecraft.ChatFormatting.GRAY)));
    }

    /**
     * Put a line in the player's own chat.
     *
     * <p>The client-side chat log, not a network message: nothing this mod says
     * is visible to anyone else on the server, which is the correct behaviour
     * for a tool that only ever acted on this machine.
     */
    private void tell(Minecraft minecraft, Component message) {
        // 26.2 split the old addMessage in two. addClientSystemMessage is the
        // correct half: it marks the line as coming from this machine, so it is
        // never confused with something the server said.
        if (minecraft.gui != null && minecraft.gui.hud != null) {
            minecraft.gui.hud.getChat().addClientSystemMessage(message);
        } else {
            AlaScreen.LOGGER.info("{}", message.getString());
        }
    }
}
