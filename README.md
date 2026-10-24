# Blocky Music — rhythm battles for Minecraft

Use the microphone on a player or mob, choose a beatmap from the Workshop, and let the battle begin.

Blocky Music это мод для Minecraft 1.21.1 на NeoForge: ритм-битвы с игроками и мобами, Мастерская карт прямо в игре,
редактор карт и DJ-стойка с колонками, камерами и визуальными эффектами. Подробное описание по-русски:
[docs/OVERVIEW.ru.md](docs/OVERVIEW.ru.md).

**[Download on Modrinth](https://modrinth.com/mod/blocky-music)** · Minecraft 1.21.1 · NeoForge · client and server

This repository is the source of version **0.1.0-beta.1**, published so it can be read, built and checked.

## Features

- Battles in the style of your favorite rhythm games, against other players or mobs, or in solo play.
- Battle setup: before each battle, both sides choose a beatmap from the server's list or directly from the
  Workshop, along with gameplay modifiers.
- Workshop: open it with Alt+B or the button on the title screen or in the pause menu. Find the beatmap you want and
  download it directly in the game.
- In-game publishing: a step-by-step wizard lets you upload your chart, song and cover art to the Workshop without
  leaving Minecraft.
- Beatmap and FX editor with support for importing osu! beatmaps (.osu / .osz) and Friday Night Funkin' charts, as
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

On Windows the same checks run as `tools/check-*.ps1`. Both sides of a game need the same build: the network protocol
changes between builds.

## Licence

- Code: MIT, see [LICENSE](LICENSE).
- Original graphics, models, animations and sounds: CC BY-NC 4.0, see [LICENSE-ASSETS.md](LICENSE-ASSETS.md).
- Third-party material and the exceptions, file by file: [NOTICE.md](NOTICE.md), also on the
  [licence page](https://qwu.world/mods/minecraft/blocky-music/license).

**Not in this repository.** Three files are left out on purpose, and a build from this source runs without them:

- `sounds/entity/smiley_damage.ogg` – a SampleFocus sample. Its licence allows it inside the mod but not on its own,
  so only the release jar carries it; a build from source plays no sound for that event.
- `textures/entity/test_rap_god_master.png` – the test NPC's skin, still waiting for confirmation of where it came
  from (see NOTICE.md, *Pending confirmation*).
- `player anims.bbmodel` – a Blockbench file started from a template of unknown origin; the mod does not use it.

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
in-game and approved by people. The icon was commissioned from an artist; the textures, models, music and sounds were
created without generative AI.

Blocky Music is not affiliated with Mojang Studios, Microsoft, ppy Pty Ltd (osu!) or The Funkin' Crew (Friday Night
Funkin').
