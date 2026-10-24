# Blocky Music on Modrinth: the listing, ready to paste

Every field below is written to the rules in `RELEASE-MODRINTH.md`: the summary is English, at least 25
characters, carries no markup, no links and does not repeat the name; the description is English first with the
Russian under it, over 125 characters of real text, never two headings of the same level in a row, and does not end
on a heading. Nothing here was made by a generative model in the sense Modrinth's AI policy means: the wording is
ours, and the icon and any gallery images must be ours too.

Claims are checked against what the mod actually does today. Do not add a feature to this page before it is in the
jar being uploaded, because rule 5.1 is about the listing, the jar and the licence page saying the same thing.

---

## Name

```
Blocky Music
```

## Slug

```
blocky-music
```

## Summary

```
Rhythm battles in Minecraft: four-lane note duels against players and mobs, a DJ booth with speakers and cameras, and a built-in chart editor.
```

## Description

Paste as Markdown. The English half comes first; the Russian half repeats it rather than adding anything, which is
what a Russian-speaking player expects to find and what keeps the two halves from drifting apart.

```markdown
Challenge a player or a mob with the microphone and the battle starts. Notes fall in four lanes, you set the keys,
the colours, the note shape and the scroll speed, and calibration keeps the sound and your input honest against
each other.

## What is in it

- **Four-lane battles** against another player or a mob, solo practice, and a stage the audience can watch.
- **A chart editor** in game, with imports from other rhythm games, so a song you have becomes a map you play.
- **A DJ booth**: a playlist, an equalizer, visual effects, speakers that place the sound in the world, and cameras
  that give the audience an angle.
- **The Workshop**, opened over the game with Alt+B: search for maps, hear a thirty-second preview, install in one
  press, read a map's page with its ratings and comments, and publish your own charts without leaving Minecraft.
- **Server maps**: a server keeps its own list, and everyone connected sees and plays it.

## The music is yours

No music ships with this mod, and none is downloaded from us without you asking for it. You bring your own files,
or you install a map somebody published in the Workshop. You are responsible for what you upload and for having the
right to use it. Rights holders can ask us to remove a map, and the Workshop's rules and legal documents say how.

## Before you install

- **Both sides need it.** The mod runs on the client and the server, and the network protocol changes between
  builds, so a server and its players must be on the same one.
- **Java 21**, Minecraft 1.21.1, NeoForge.
- **playerAnimator is required** (by KosmX, project `playeranimator`). Take the build for 1.21.1.
- **FFmpeg is optional.** Installing a map, playing it and saving a chart need none. The chart editor runs the
  FFmpeg already installed on your computer to turn MP3 and other audio into Ogg, including the audio of an
  imported osu! beatmap. A server, and your own single-player world is one, runs it to make the 1.5x and 2x
  copies of a song; without it those battles play at normal speed and say so in chat. If your system has none,
  put one in `ffmpeg/bin/` next to the game.
- **The Workshop asks before it uses the network.** Nothing is sent until you allow it on its first-run screen.
- Uploading, commenting and rating on the Workshop are for players aged 16 and over; browsing, downloading and
  playing are not.

## Licence

Our code is MIT, our own art, models, animations and sounds are CC BY-NC 4.0, and the exceptions are listed by
name: <https://qwu.world/mods/minecraft/blocky-music/license>

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT. Not affiliated with
ppy Pty Ltd (osu!) or The Funkin' Crew (Friday Night Funkin'). Contact: it.qwuworld@gmail.com

---

## По-русски

Ткните микрофоном в игрока или моба, и начнётся битва. Ноты падают по четырём дорожкам, а клавиши, цвета, форму нот
и скорость вы задаёте сами; калибровка отдельно правит задержку звука и задержку ввода.

### Что внутри

- **Битвы на четыре дорожки** с другим игроком или мобом, одиночная тренировка и сцена, за которой смотрят
  зрители.
- **Редактор карт** прямо в игре, с импортом из других ритм-игр.
- **DJ-стойка**: плейлист, эквалайзер, визуальные эффекты, колонки, расставляющие звук по миру, и камеры для
  зрителей.
- **Мастерская** по Alt+B: поиск карт, превью на тридцать секунд, установка одним нажатием, страница карты с
  оценками и комментариями и публикация своих чартов, не выходя из Minecraft.
- **Карты сервера**: у сервера свой список, его видят и играют все, кто на него зашёл.

### Музыка ваша

Мод не приносит с собой ни одного трека и ничего не скачивает без вашего запроса. Вы берёте свои файлы или ставите
карту, которую кто-то опубликовал в Мастерской. За то, что вы загружаете, отвечаете вы. Правообладатель может
попросить убрать карту, и правила Мастерской описывают, как это происходит.

### Перед установкой

- **Нужен обеим сторонам.** Мод работает на клиенте и на сервере, сетевой протокол меняется между сборками,
  поэтому сборка у сервера и у игроков должна быть одна.
- **Java 21**, Minecraft 1.21.1, NeoForge.
- **Нужен playerAnimator** (KosmX, проект `playeranimator`), сборка под 1.21.1.
- **FFmpeg не обязателен.** Чтобы поставить карту, сыграть её и сохранить чарт, он не нужен. Редактор запускает
  FFmpeg, который уже стоит у вас на компьютере, чтобы перевести MP3 и другой звук в Ogg, в том числе звук
  импортированной карты osu!. Сервер, и ваш одиночный мир тоже, делает им копии песни на 1.5x и 2x; без него
  такие бои идут на обычной скорости, и чат об этом говорит. Если FFmpeg в системе нет, положите его в
  `ffmpeg/bin/` рядом с игрой.
- **Мастерская спрашивает перед выходом в сеть.** Пока вы не разрешите на первом экране, наружу не уходит ничего.
- Загружать, комментировать и оценивать в Мастерской можно с 16 лет; смотреть, скачивать и играть — без
  ограничения.

### Лицензия

Код — MIT, собственные графика, модели, анимации и звуки — CC BY-NC 4.0, исключения перечислены поимённо:
<https://qwu.world/mods/minecraft/blocky-music/license>

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT. Мод не связан с ppy
Pty Ltd (osu!) и The Funkin' Crew (Friday Night Funkin'). Связь: it.qwuworld@gmail.com
```

## Content disclosures

**Network access (opt-in)** — paste into that block, word for word; it matches the mod's own first-run screen:

```
Nothing is sent until you allow it on the Workshop's first-run screen. After that the mod contacts
blocky-music.qwu.world to browse, download and check updates of beatmaps, and for uploads, comments and ratings;
sessionserver.mojang.com only when you sign in with your Minecraft account (your Minecraft access token goes only
to Mojang); api.minecraftservices.com and textures.minecraft.net for skin heads of server-comment authors. These
services see your IP address and the mod version; the Workshop also receives your searches, the maps you install
or check, and your QwU World account token after you sign in. Installed maps, drag-and-drop and the inbox folder
work without network access. You can turn network access off in the Workshop at any time.
```

**External system interactions** — this block has to be filled in, because the mod starts a program that is not
part of the game. Paste it word for word:

```
Blocky Music runs FFmpeg as a separate program - the one already installed on your own computer - in three
places: converting an audio file to Ogg Vorbis in the chart editor, taking the audio out of an imported osu!
beatmap when it is not Ogg already, and, on a server (a single-player world runs one too), rendering the 1.5x
and 2x copies of a track in the background the first time a battle or the DJ booth asks for that speed.
Without FFmpeg a battle plays at 1x and says so. The mod never bundles FFmpeg and never downloads it: it looks
for one only when one of those things is asked for, and says so plainly when there is none. Browsing,
installing and playing beatmaps and saving a chart need no FFmpeg at all. It is looked for inside the game
folder first
(ffmpeg/bin/, ffmpeg/, music_battle_raw/, the game folder, mods/), then where each system keeps it
(C:\ffmpeg\bin and C:\Program Files\ffmpeg\bin on Windows; /opt/homebrew/bin, /usr/local/bin, /usr/bin and
/snap/bin on macOS and Linux), and last on PATH; each candidate is started once with -version so a broken one
is not trusted.

The mod opens your system's own file picker when you press "Import .blockymap..." or choose audio or a cover
image for an upload, so you can pick a file from anywhere on your computer; the mod reads that file and copies
what it needs into the game folder. "Open folder" asks your operating system to show one of the mod's own
folders, and links open in your browser through Minecraft's own confirmation dialog.

What the mod keeps, it keeps inside the Minecraft folder. Two things are written outside it and only for a
moment: importing a beatmap's audio and preparing an upload preview each put one temporary file in the system
temporary folder and delete it again. The mod registers nothing with the operating system - no file type, no
URL scheme, no registry or startup entry. The .blockymap file type and the blockymusic:// link belong to the
separate helper program, which the player installs themselves.
```

**Telemetry** — leave empty. Nothing is collected beyond what the two blocks above describe.

## Version

| Field | Value |
|---|---|
| Version number | `0.1.0-beta.1` |
| Channel | `beta` |
| Loader | `neoforge` only |
| Game version | `1.21.1` only |
| Environment | Client and server, required on both |
| File | `build/libs/blocky_music-0.1.0.jar`, rebuilt and checked per `RELEASE-MODRINTH.md` point 6 |
| Dependency | playerAnimator, **required**, project `gedNE4y2`, build `2.0.4+1.21.1-forge` (`HJZB6bmA`) |

## Licence fields

- **Custom**, tick "License does not have a SPDX identifier".
- Name: `Blocky Music License`.
- URL: `https://qwu.world/mods/minecraft/blocky-music/license` — live since 19 September 2026.

## What is still on the owner

- **The icon.** Modrinth takes up to 256 KiB and nothing made by a generative model. The mod's own logo,
  `src/main/resources/blocky_music_logo.png` (512×512 PNG, 155 KiB, the `logoFile` the NeoForge mod list
  shows), fits that limit and can be uploaded as is, provided the owner confirms it was not generated.
- **Rebuild the jar and re-publish the licence page** before the upload, in that order: the candidate jar in
  `build/libs` predates the texture, the font licence filename and this listing, so it still disagrees with
  every document. `RELEASE-MODRINTH.md` points 6 and 7 are the checks.
