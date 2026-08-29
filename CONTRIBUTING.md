# Contributing to Ala Screen

Thanks for looking. Ala Screen is a client-side Minecraft mod that turns a
screenshot into a shareable link. Bug reports, fixes and translations are all
welcome.

## Getting started

Requires **JDK 25** — Minecraft 26.x will not start on anything older.

```bash
./gradlew :fabric:build     # build the jar
./gradlew :common:test      # unit tests, no game needed
./gradlew :fabric:runClient # dev client
```

The dev environment is self-contained: worlds, configs and screenshots live under
`fabric/runs/client/` and never touch a real Minecraft installation.

## How the code is laid out

| Module | Contains | Minecraft on the classpath |
|---|---|---|
| `common` | HTTP, multipart, config, history, locale mapping | no |
| `common-mc` | capture, the confirmation screen, chat, the whole flow | yes, via NeoForm |
| `fabric` | entrypoint, key binding, client command, one mixin | yes |

`common` and `common-mc` are consumed as **source**, so each loader recompiles
them with its own toolchain. Write a feature once, in the shared module; a
loader module is an entrypoint and a handful of lines.

**If your change landed in `fabric/`, ask yourself why it is not in `common` or
`common-mc`.** Usually it should be.

## What a good change looks like

- **Tests for logic that can be tested without the game.** `:common` runs in
  milliseconds and has no Minecraft on its classpath; anything that fits there
  belongs there.
- **API signatures checked, not remembered.** Minecraft 26.2 rewrote the GUI
  layer — `GuiGraphics` is gone, `blit` takes corners rather than a size, and
  key categories are Identifier-backed records. Verify against the jar with
  `javap` before writing against it.
- **Nothing uploaded without a person asking for it.** The mod shows the frame
  and waits. That is not a preference, it is the reason the mod is safe to use:
  no rule can scan pixels for coordinates, chat or a server address.

## Translations

Language files live in `common/src/main/resources/assets/alascreen/lang/`. Seven
locales ship today: `en_us`, `ru_ru`, `uk_ua`, `de_de`, `fr_fr`, `es_es`,
`ja_jp`.

Every file must carry the **same set of keys** — a missing key falls back to the
key name and reaches the player as `alascreen.chat.uploaded`. Use indexed
placeholders (`%1$s`, `%2$s`) so a translation can reorder them.

## Reporting a bug

Please include the Minecraft version, the loader, the mod version and the
relevant part of `logs/latest.log`. If the upload failed, the message in chat
names the reason — quote it, the wording distinguishes cases that look alike.

## Licence

By contributing you agree that your work is released under the MIT licence that
covers this project.
