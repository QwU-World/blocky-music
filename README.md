# Blocky Music — rhythm battles for Minecraft

Use the microphone on a player or mob, choose a beatmap from the Workshop, and let the battle begin.

Blocky Music это мод для Minecraft 1.21.1 на NeoForge: ритм-битвы с игроками и мобами, Мастерская карт прямо в игре,
редактор карт и DJ-стойка с колонками, камерами и визуальными эффектами. Подробное описание по-русски:
[docs/OVERVIEW.ru.md](docs/OVERVIEW.ru.md).

**[Download on Modrinth](https://modrinth.com/mod/blocky-music)** · Minecraft 1.21.1 · NeoForge · client and server

This repository is the source of version **0.2.0-beta.2**, published so it can be read, built and checked. The code
is MIT and the mod's own assets are CC BY-NC 4.0, so the mod as a whole is not open source; see [Licence](#licence).

## Features

- Battles in the style of your favorite rhythm games, against other players or mobs, or in solo play.
- Battle setup: before each battle, both sides choose a beatmap from the server's list or directly from the
  Workshop, along with gameplay modifiers.
- Workshop: open it with Alt+B or the button on the title screen or in the pause menu. Find the beatmap you want and
  download it directly in the game.
- In-game publishing: a step-by-step wizard lets you upload your beatmap, song and cover art to the Workshop without
  leaving Minecraft.
- Beatmap and FX editor with support for importing osu! beatmaps (.osu / .osz) and Friday Night Funkin' beatmaps, as
  well as creating beatmaps from scratch.
- DJ booth: play music at parties without challenging anyone to a battle. A playlist you can add songs to from the
  Workshop, an equalizer, visual effects, speakers that extend the music's range, and cameras spectators can connect to.
- Server beatmaps: each server has its own list of beatmaps.

## What is here

- `src/main/java/ru/gora/musicbattle/` – the mod, client and server in one jar.
- `src/main/resources/` – assets, translations (English and Russian) and data.
- `tools/` – the regression checks, which run outside Minecraft, plus the font build and the texture generator.
- `docs/` – the Workshop's design (`WORKSHOP.md`), licensing notes and the Russian overview. `DEVELOPMENT.md` in the
  root maps the subsystems (in Russian).

The Workshop service at [blocky-music.qwu.world](https://blocky-music.qwu.world) is a separate project and is not in
this repository.

## Building

JDK 21; Gradle fetches the rest.

```bash
./gradlew build                      # build/libs/blocky_music-<version>.jar
python3 tools/run-check.py all       # every regression check (macOS and Linux)
```

On Windows the checks are the PowerShell scripts `tools/check-*.ps1` themselves (`run-check.py` reads the same
scripts). They take the JDK 21 from `BLOCKY_JDK`, `JAVA_HOME` or the `javac` on `PATH`, in the order `run-check.py`
uses, and the libraries from Gradle's cache in `%USERPROFILE%\.gradle` (run `gradlew.bat build` once first); many
of them also need ripgrep (`rg`) on `PATH`. This release was checked with `run-check.py` on macOS, not on Windows.
Both sides of a game need the same build: the network protocol
changes between builds (0.2.0-beta.2 speaks protocol 69).

## Licence

- Code: MIT, see [LICENSE](LICENSE).
- Original graphics, models and animations: CC BY-NC 4.0, see [LICENSE-ASSETS.md](LICENSE-ASSETS.md).
- Third-party material and the exceptions, file by file: [NOTICE.md](NOTICE.md), also on the
  [licence page](https://qwu.world/mods/minecraft/blocky-music/license).

**Not in this repository.** Two files are left out on purpose: `sounds/entity/smiley_damage.ogg`, a SampleFocus
sample, and `tools/vorbis-decoder-support/smiley_damage.libvorbis.f32`, the same sound decoded for a check. The
sound stays in the mod by QwU World's decision: the release jar uses it inside the mod under the SampleFocus
Standard License, and it must not be extracted from the mod or redistributed. A build from this source runs
without it, and the smiley ball then plays no sound when it is hurt or dies. In a copy of this repository,
`python3 tools/run-check.py all` passes 103 of its 104 checks (measured on macOS): `smiley-sounds` fails its
`SmileySoundAssetCheck` half, which looks for that `.ogg`. Three checks pass with a SKIP line:
`native-vorbis-decoder` skips the parts that read the two files, and `beat-one` and `chart-offset` skip their
comparisons with an older build unless `BLOCKY_PREVIOUS_JAR` names one.

## Links

- Website: [qwu.world](https://qwu.world) · Mod page: [qwu.world/mods/minecraft/blocky-music](https://qwu.world/mods/minecraft/blocky-music)
- Workshop: [blocky-music.qwu.world](https://blocky-music.qwu.world) · Privacy: [qwu.world/privacy](https://qwu.world/privacy#blocky-music-workshop)
- Forum: [qwu.world/forum](https://qwu.world/forum) · Discord: [discord.gg/sSENQuz6a7](https://discord.gg/sSENQuz6a7)
- Telegram: [t.me/qwuworld](https://t.me/qwuworld) · X: [@QwU_World](https://x.com/QwU_World) · Patreon: [QwUWorld](https://www.patreon.com/QwUWorld)

## Contact

Bugs and ideas: [issues](https://github.com/QwU-World/blocky-music/issues), the [forum](https://qwu.world/forum) or
`#bugs-suggestions` in [Discord](https://discord.gg/sSENQuz6a7). Anything else: it.qwuworld@gmail.com.

## How the mod is made

The QwU World team designs, oversees and tests Blocky Music. Since September 2026, an AI assistant has written and
reviewed parts of the code and this text, always under the team's direction. Every feature was specified, tested
in-game and approved by people. The icon was commissioned from an artist; the textures, models and animations were
made without generative AI. Blocky Music ships no music; its one sound effect is a SampleFocus sample (see
[NOTICE.md](NOTICE.md)).

Blocky Music is not affiliated with Mojang Studios, Microsoft, ppy Pty Ltd (osu!) or The Funkin' Crew (Friday Night
Funkin').
