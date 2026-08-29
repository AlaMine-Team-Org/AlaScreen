package day.alacraft.alascreen.mc;

import day.alacraft.alascreen.AlaScreen;
import day.alacraft.alascreen.Config;
import day.alacraft.alascreen.UploadService;
import day.alacraft.alascreen.history.UploadRecord;
import day.alacraft.alascreen.i18n.SiteLocale;
import com.mojang.blaze3d.platform.NativeImage;
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

    public ShareController(Config config,
                           UploadService service,
                           Path configFile,
                           Path screenshotsDirectory) {
        this.config = config;
        this.service = service;
        this.configFile = configFile;
        this.screenshotsDirectory = screenshotsDirectory;
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

                    tell(minecraft, Feedback.uploaded(result));

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

        Path path = screenshotsDirectory.resolve(fileName).normalize();

        // The name arrives through a chat command, so it is caller-controlled.
        // Without this, "../../.ssh/id_rsa" would be a valid screenshot to share.
        if (!path.startsWith(screenshotsDirectory.normalize())) {
            busy.set(false);
            tell(minecraft, ChatFormat.error("alascreen.error.not_found"));
            return;
        }

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
