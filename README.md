# Ala Screen

Share a Minecraft screenshot with one key.

```
Saved screenshot as 2026-08-29_17.42.01.png [share]     <- vanilla F2, plus one word
[Ala Screen] Taking the shot...
[Ala Screen] Also saved as 2026-08-29_17.42.01.png
   -> the shot appears, with "Upload  /  Save without uploading  /  Cancel"
[Ala Screen] Uploading 2026-08-29_17.42.01.png (2.4 MB)...
[Ala Screen] Screenshot uploaded: alacraft.day/en/screenshots/aB3xY9kM [open] [copy link] [copy image] [delete]
```

You see the frame before it leaves the machine. Then it goes to
[alacraft.day](https://alacraft.day/en/screenshots), which answers with a short
link in the player's own language — and that link unfurls in Discord as the
picture itself.

## What you get

- **You look at it first.** There are no redaction rules for pixels. A shot can
  carry chat, an F3 overlay, a server address or someone's name, and no regular
  expression will find them — so the default is a preview and a question. It can
  be turned off; it is not off for you.
- **F2 shows you what it caught.** Vanilla saves the file and says so in chat;
  the mod adds a small plaque in the corner with the shot itself and the key that
  shares it. No reaching for the mouse, and no wondering which of six screenshots
  the chat line means. It goes away on its own, respects your Notification Time
  setting, and is one line in the config away from never appearing.
- **Fourteen languages.** Every line the mod prints follows the client's language,
  and so does the link: a Russian player is handed `/ru/screenshots/...`, not the
  English page.
- **It says what it is doing.** Taking, saving, uploading, done. A key that sits
  silent for four seconds looks broken.
- **Errors you can act on.** Offline, rate limited, too large, too many pixels
  and "a moderator removed it" are five different messages, because they need
  five different responses — and three of them are not your fault.
- **A local copy, as always.** The file still lands in `screenshots/`, named the
  way vanilla names them. Pressing a screenshot key and ending up with no file
  would astonish anyone.
- **The picture on the clipboard, not just the link.** `[copy image]` puts the
  shot itself there, so it can be pasted straight into a chat client without
  opening the page first.
- **Deleting your own upload survives a restart.** The delete token is kept on
  disk, so the link you shared this morning is still yours tonight.
- **Nothing is uploaded on its own.** A screenshot leaves the machine when
  someone asks, and not before.

## Status

Minecraft 26.2, **Fabric and NeoForge**, client-side only — it
works in singleplayer, on a vanilla server, and on a server that has never heard
of it.

Both loaders ship the same build. Everything a player interacts with lives in
`common` and `common-mc` and is compiled from one source by each loader, so the
two entrypoints are thin adapters rather than two implementations: they differ
only where the loaders themselves differ — where the game directory comes from,
how a key binding is registered, which command-source type the dispatcher
carries. A check in CI compares them on every push, because the way this
arrangement fails is not a build error but a forgotten call on one side.

## Layout

| Module | Contains | Minecraft on the classpath |
|---|---|---|
| `common` | HTTP, multipart, config, history, locale mapping | no |
| `common-mc` | capture, the confirmation screen, chat components, the flow | yes, via NeoForm |
| `fabric` | entrypoint, key binding, mixin | yes |
| `neoforge` | the same, for the other loader | yes |

`common` and `common-mc` are consumed as **source**, so every loader recompiles
them with its own toolchain — one body of logic, no shading, no intermediate
artifact.

## Building

Requires JDK 25.

```bash
./gradlew :fabric:build :neoforge:build   # jars in <loader>/build/libs/
./gradlew :common:test                    # unit tests, no game needed
./gradlew :fabric:runClient               # dev client (or :neoforge:runClient)
python3 tools/loader_parity_check.py      # the entrypoints still agree
```

More in [docs/DEV-RUN.md](docs/DEV-RUN.md); API signatures verified against the
26.2 jar are in [docs/MC-26.2-API.md](docs/MC-26.2-API.md) — 26.2 rewrote the GUI
layer, so that file is not optional reading.

## Configuration

`config/alascreen.json`, created on first run:

| Key | Default | What it does |
|---|---|---|
| `apiBaseUrl` | `https://alacraft.day/api/v1` | Point it at a self-hosted instance if you run one |
| `apiToken` | empty | Personal token from [alacraft.day/profile](https://alacraft.day/profile): attaches uploads to your account and raises the rate limit |
| `confirmBeforeUpload` | `true` | Show the shot and ask first |
| `copyLinkToClipboard` | `true` | Put the link on the clipboard when it arrives |
| `saveLocalCopy` | `true` | Also keep the file in `screenshots/` |
| `persistHistory` | `true` | Remember upload ids and delete tokens across restarts |

The key binding defaults to **F7** — next to the screenshot key it belongs with,
and free in 26.2 (F6 is not: it opens the debug options). Rebind it under
Options → Controls → *Ala Screen*, or ignore it entirely: pressing F2 gives you
the same offer in chat.

There is one request nobody triggers, and it is easier to name it than to let you
find it: on startup the mod calls `GET /screenshots/limits` once, to learn the
current maximum upload size rather than discovering it during the first upload of
the session. It sends no image and no identifier — the User-Agent carries the mod
version, the Minecraft version and the loader name — and, like any HTTP request,
it shows the server where it came from. `apiBaseUrl` redirects this request too,
so a self-hosted instance keeps it in-house.

## Licence

MIT — see [LICENSE](LICENSE).
An independent project by AlaCraft, not affiliated with Mojang Studios or Microsoft.
