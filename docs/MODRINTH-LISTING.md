# Blocky Music on Modrinth: the listing, ready to paste

This is the listing as it goes on Modrinth, field by field. It follows `RELEASE-MODRINTH.md` and Modrinth's Content
Rules: the summary is English, at least 25 characters, carries no markup or links and does not repeat the name; the
description has more than 125 characters of real text, never puts two headings of the same level in a row and does
not end on a heading. The description says how the mod is made, and the AI-generated content disclosure is on for
code and text (rule 6.1). The icon was commissioned from an artist; the textures, models and animations are not
made with generative AI. The mod ships no music, and its one sound effect is a SampleFocus sample.

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
Rhythm battles in Minecraft: four-lane note duels against players and mobs, beatmaps from an in-game Workshop, a DJ booth with speakers and cameras, and a beatmap editor.
```

## Description

Paste as Markdown.

```markdown
Use the microphone on a player or mob, choose a beatmap from the Workshop, and let the battle begin!!!

## Features

- Battles in the style of your favorite rhythm games, against other players or mobs, or in solo play.
- Battle setup: before each battle, both sides choose a beatmap from the server's list or directly from the Workshop, along with gameplay modifiers.
- Workshop: open it with Alt+B or the button on the title screen or in the pause menu. Find the beatmap you want and download it directly in the game.
- In-game publishing: a step-by-step wizard lets you upload your beatmap, song, and cover art to the Workshop without leaving Minecraft.
- Beatmap and FX editor with support for importing osu! beatmaps (.osu / .osz) and Friday Night Funkin' beatmaps, as well as creating beatmaps from scratch.
- DJ booth: play music at parties without challenging anyone to a battle. The DJ booth includes a playlist you can add songs to from the Workshop, an equalizer, and visual effects. You can also connect speakers to extend the music's audible range, as well as cameras that spectators can connect to, making sure the camera operator is earning their paycheck.
- Server beatmaps: each server has its own list of beatmaps. Only server operators, and players they allow, can add beatmaps to the server's list.

## Music from the community

The mod comes with no songs. You use your own files or install the beatmaps you choose from the Workshop. In multiplayer, the server you play on also sends you the song of a battle you are in or can hear, and of a DJ booth playing near you.

You are responsible for what you upload. Copyright holders can request the removal of a beatmap; the Workshop rules explain how.

## Before installing

- Required on both the client and the server. The network protocol changes between builds, so the server and all players must use the same build.
- Java 21, Minecraft 1.21.1, NeoForge.
- [playerAnimator](https://modrinth.com/mod/playeranimator) by KosmX is required on the client. Use the build for Minecraft 1.21.1.
- No FFmpeg or other programs needed: the mod converts MP3, WAV, FLAC, M4A and AAC (Apple Lossless too), Opus, WebM, MKV and MPEG-TS songs to Ogg itself, and makes the 1.5x and 2x speed versions of your beatmaps the same way. Other formats, such as WMA, need converting with another program first.
- The Workshop sends nothing until you allow network access on its first-run screen, and you can turn access off again with the Network access switch on the Workshop's account page. Once allowed, it connects to [blocky-music.qwu.world](https://blocky-music.qwu.world), loads account pictures from qwu.world, and contacts Mojang to sign you in with your Minecraft account and to show the skins of players who wrote server comments. It signs a Minecraft account in by itself, once per game launch, only if that account has already signed in to the Workshop on this computer, and stops when you sign out; if you have never signed in, nothing is sent for this. Read the [privacy policy](https://qwu.world/privacy#blocky-music-workshop).
- You must be at least 16 to upload, comment, or rate; browsing, downloading, and playing are open to everyone.
- This is a beta: there may be rough edges. Back up your worlds, report bugs and errors, and share your ideas on our [forum](https://qwu.world/forum).

## License

- Code: MIT.
- Original graphics, models and animations: CC BY-NC 4.0.

Exceptions are listed individually on the [license page](https://qwu.world/mods/minecraft/blocky-music/license).

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT. The mod is not affiliated with ppy Pty Ltd (osu!) or The Funkin' Crew (Friday Night Funkin').

Contact: [it.qwuworld@gmail.com](mailto:it.qwuworld@gmail.com)

## How the mod is made

The QwU World team designs, oversees, and tests Blocky Music.

Since September 2026, an AI assistant has written and reviewed parts of the code and this text, always under the team's direction. Every feature was specified, tested in-game, and approved by people.

The icon was commissioned from an artist; the textures, models and animations were made without generative AI. Blocky Music ships no music; its one sound effect is a SampleFocus sample, credited on the license page.
```

## Content disclosures

Modrinth's Settings → Disclosures has these switches: AI-generated content, AI functionality, Advertisements, Paid
features, Telemetry, Derivative content, Photosensitivity, External system interactions and Archive. There is no
separate switch for network access: the Workshop's network use is disclosed under Telemetry.

**Telemetry: on, consent model opt-in.** Rule 1.11 forbids uploading data to a server the player did not choose to
connect to in game without clear disclosure. One item per line, word for word; the first-run screen in the mod says
the same:

```
The Workshop sends nothing until you allow network access on its first-run screen. You can turn it off again at any time with the Network access switch on the Workshop's account page (the button at the top right); downloads in progress are then cancelled.
blocky-music.qwu.world (QwU World) receives your IP address; the mod, Minecraft and NeoForge versions and your operating system's name (in the User-Agent of every request, and as the device name of a sign-in); your searches; the beatmaps you install or check for updates; your Minecraft name and UUID when you sign in with Minecraft; and, after you sign in, your QwU World account token. Download counts and rate limits store only a shortened IP prefix. Privacy notice: https://qwu.world/privacy#blocky-music-workshop
Automatic sign-in: only for a Minecraft account that has already signed in to the Workshop on this computer, which the mod remembers by its UUID in config/music_battle-workshop.json. For that account, the mod sends its Minecraft name and UUID again to sign you in, once per game launch when you open the Workshop (and once more a few seconds later if another login got in the way). Signing out forgets the account. A player who has never signed in sends nothing automatically.
qwu.world (QwU World): account pictures. The mod loads pictures only over https from qwu.world and its subdomains.
Mojang: sessionserver.mojang.com to sign you in with your Minecraft account (your Minecraft access token goes only to Mojang, never to QwU World), and api.minecraftservices.com, sessionserver.mojang.com and textures.minecraft.net to show the skin heads of server-comment authors. The api.minecraftservices.com lookup carries the same User-Agent.
Outside the Workshop and its switch: the test NPC's settings screen previews a skin set by a player name. It looks the typed name up at api.minecraftservices.com with the same User-Agent and loads that skin from sessionserver.mojang.com and textures.minecraft.net. In the world, the NPC's skin loads the way Minecraft loads any player's skin.
No analytics, no advertising, nothing sold. Without network access you can still install .blockymap files by drag-and-drop or the inbox folder and play your own beatmaps; a Workshop beatmap needs an online check at least once a week before it can be brought into a battle, unless the server already has it.
```

**External system interactions: on**, because the mod lets you pick a file from anywhere on your computer and asks
the operating system to show a folder:

```
Blocky Music converts MP3, WAV, FLAC, M4A, AAC, Opus, WebM, MKV and MPEG-TS audio to Ogg and makes the 1.5x and 2x song copies with its own code, inside the game. It starts no program for that, and no FFmpeg is needed.
The system file picker opens when you import a .blockymap, pick the song for a Workshop beatmap published without one, choose a cover for an upload, or choose a skin file for the test NPC. The mod reads the chosen file and keeps what it needs in the game folder, or on the server for an NPC skin. The picker comes from LWJGL, which ships with Minecraft; on macOS and Linux it is shown through osascript, zenity or kdialog. "Open folder" asks the OS to show one of the mod's folders, and links open in your browser after Minecraft's confirmation, both the way Minecraft itself opens folders and links.
Everything is kept inside the Minecraft folder, temporary files too. The picture prepared for "Publish to server", and the song you pick for a Workshop beatmap published without one while it is converted, go into music_battle_tracks/.temp and are deleted when they are done with; whatever a killed game left there is deleted at the next start. Other song conversions (the editor, an osu! import, the 1.5x and 2x copies) write the unfinished file next to the finished song, under a name that starts with a dot and ends in .part, and delete it when they are done; one a killed game left behind is deleted by a later conversion into the same folder. The mod registers no file types, URL schemes or startup entries. It does not use the microphone or camera; the "microphone" is an in-game item.
```

**Photosensitivity: on.** The DJ booth's Flash effect flashes to the beat or fills the screen with colour; at 180 BPM
and above (90 BPM at 2x speed) that is three or more full-screen flashes a second, Modrinth's threshold:

```
DJ booth visual effects (Flash, Pulse, Shake, colour cycling) can flash the whole screen in time with the music, faster than three times per second at high tempos. A beatmap's own effects can do the same in a battle. Players in a battle always see the chosen beatmap's effects; spectators and players near a DJ booth can turn these effects off with "Spectator effects" in the mod's settings, and a DJ can play any playlist entry with No FX. Uploaders mark Workshop beatmaps whose effects flash more than three times per second as "Flashing effects", and players can report a beatmap that is not marked.
```

**AI-generated content: on, with `code` and `text` ticked and `assets` off.** Rule 6.1 asks for it when a substantial
part of the code or the page is AI output. Rule 6.2 only hides projects that are primarily or entirely AI output,
which Blocky Music is not: people design, direct and play-test it, and people made the art.

**Off:** AI functionality, Advertisements, Paid features, Archive, and Derivative content. Blocky Music is not a fork,
port or modpack of another project. Its third-party parts are credited file by file in `META-INF/NOTICE.md`: the
Nunito font; the Lucide icons; easing code that follows osu-framework; the audio decoders (a Java port of the
libopus decoder under BSD-3-Clause, AAC and Apple Lossless code under Apache-2.0, libvorbis values under
BSD-3-Clause, and ports of the public-domain stb_vorbis and minimp3); the public-domain Freeverb reverb values and
an integer hash; Inigo Quilez's rounded-box distance formula in the interface shaders; parts of the NeoForge MDK
template; one Minecraft expression in a mixin and Minecraft's model boilerplate; and the one SampleFocus sound.

## Version

| Field | Value |
|---|---|
| Version number | `0.2.0-beta.2` |
| Version name | `Blocky Music 0.2.0-beta.2` |
| Channel | `beta` |
| Loader | `neoforge` only |
| Game version | `1.21.1` only |
| Environment | Client and server, required on both |
| File | `build/libs/blocky_music-0.2.0-beta.2.jar`, rebuilt and checked per `RELEASE-MODRINTH.md` point 6 |
| Dependency | playerAnimator, **required**, project `gedNE4y2`, build `2.0.4+1.21.1-forge` (`HJZB6bmA`) |

## Changelog for 0.2.0-beta.2

Paste this into the version's Changelog field. It covers everything since 0.1.0-beta.1.

```markdown
**Battles**
- When you challenge a player or a mob, you choose whether you are Player 1 or Player 2.
- Players and humanoid mobs hold a microphone during a battle.
- The battle camera is lower and turns smoothly, and when the parts are played one after the other it turns to
  whoever is playing. A camera block can now follow the battle's players.
- Pausing a single-player game pauses the battle: the bot stays in time, nothing runs on in the background, the song
  stays on its beatmap, and holds you keep pressed are not let go.
- The vote to skip a rest works every time.
- A hold note is as long in a battle as in the editor, and notes land square on their buttons with no gap.
- Player animations always play, play fully and switch faster.
- A mob's hits, misses and broken holds look like yours, and a hold you let go too early breaks on your screen too.
- A spectator who joins a mob battle half-way sees every note correctly.
- A spectator whose download of the beatmap fails is told why and no longer holds the battle up until it times out.
- The default note speed is 5. A speed you chose yourself is kept.
- Beatmaps can say where beat 1 is. Every beat effect follows it, and old beatmaps play exactly as before.
- New achievements for 42 and 67 wins. The achievement for beating the test NPC is gone.

**Beatmap editor**
- Ctrl+A selects the page, Ctrl+drag selects a rectangle, Ctrl+V pastes into free space after the last copy and on
  the grid, and Ctrl+Z also undoes selections. On a Mac both Ctrl and Cmd work.
- Notes can be placed one after another, clicking a note's edge no longer deletes it, and right-clicking a hold
  turns it back into a normal note.
- The grid switch follows the timing, and the offset setting is clearer.
- The unsaved-changes warning shows above the editor, and English button labels fit.
- The game preview opened from the title screen has a background colour of your choice, and the FX preview with the
  World background shows the world.
- Messages are in your language, without "OK:" or "ERR:" in front, and no message or box shows the folders above
  your Minecraft folder or your user folder's name, also when a folder name has a space in it: after a save, the song
  and picture boxes show where the files are inside the Minecraft folder.

**Songs**
- Nothing to install: the mod itself converts MP3, WAV, FLAC (also as .oga), M4A, AAC, Apple Lossless, Opus, WebM,
  MKV and MPEG-TS (.ts files and HLS downloads) to Ogg, in the editor, for imported osu! beatmaps and for the 1.5x
  and 2x copies of a song. FFmpeg is no longer needed.
- Loud songs no longer distort or clip in the 1.5x and 2x copies.
- A battle that starts with the game, the Players sound slider or the mod's music muted plays its song as soon as
  you turn the volume up.

**Workshop**
- The upload window in the game works like the website's upload page, and its preview plays from the point you
  choose.
- The Workshop button is in the Esc menu on a server too, and the BM button is back on the title screen.
- Server beatmaps show their covers, and the server page opens with its comments and players' avatars. A server
  beatmap's page shows its own BPM, length and description, and every comment appears once.
- Cards keep every badge, text fits down to 320x240 in both languages, and the Published tab shows each beatmap's
  icon.
- A preview that cannot be loaded or played says so instead of spinning.
- The first-run screen says exactly what is sent and where: the hosts, the versions and system name, your Minecraft
  name and UUID at sign-in, and Mojang's part, also for the skin heads of server-comment authors. Because it
  changed, it asks once more, also if you answered it before.
- Network access can be turned off again at any time with the Network access switch on the account page.
- The Workshop signs you in automatically only with a Minecraft account that has signed in to the Workshop on this
  computer before, and stops after you sign out. If you never signed in, nothing is sent for this.
- Account pictures load only from qwu.world.
- A Workshop beatmap published without its song takes an MP3, FLAC, WAV, M4A or any other song file the mod reads,
  and the mod converts it to Ogg itself.
- The age message no longer says the whole Workshop is 16+: only uploading, commenting and rating are; browsing,
  downloading and playing are open to everyone.

**DJ booth**
- Add Workshop beatmaps to the booth with "+": they are downloaded for everyone, play like server beatmaps and are
  still there after a server restart. The booth's list holds only what you added, and × removes a beatmap.
- The booth keeps playing when you fly away and come back.
- Smiley balls look at a booth while it plays.
- Reset speakers no longer touches Visual FX. The panel keeps both columns on small windows and its mixer scrolls.

**Look and effects**
- The battle time bar, calibration, settings, DJ panel, camera, speaker, camera gallery, visual FX list and test
  NPC screens use the mod's purple look. Tips stay off the control they explain.
- "Panel Background" in the settings has a working preview. How dark the world gets in a battle can be set (0% is
  none), and the black bars can be turned off or made see-through.
- Camera setup hints are never covered by effects, and the numbers in the effect settings line up.
- A spectator's panels above the fighters stay above an effect that does not cover the interface, as your own
  panels do.
- Block outline, fish-eye and pulse work with Iris shader packs. Entity and block outlines reach only as far as the
  music can be heard and fade out near the edge.
- English says "beatmap" everywhere; Russian says «карта» and «песня» and speaks to you as «ты».

**Test NPC**
- A skin from a file as well as by name; pushing works with its AI off; its votes for random modes and its
  invulnerability work, and its random modes count in a battle you set up between two mobs too, where the chat says
  which side, Player 1 or Player 2, rolled what.

**Mod list**
- Blocky Music shows its logo, website and licence link, with its description in your language.

Network protocol 69: the server and every player need this same build. 0.1.0-beta.1 cannot join a 0.2.0-beta.2
server, and the other way round.
```

## Links

Settings → Links. The source of 0.2.0-beta.2 is on GitHub, so fill these in together with the rest of the listing:

| Field | URL |
|---|---|
| Issues | `https://github.com/QwU-World/blocky-music/issues` |
| Source | `https://github.com/QwU-World/blocky-music` |
| Discord | `https://discord.gg/sSENQuz6a7` |
| Website | `https://qwu.world/mods/minecraft/blocky-music` |

## Licence fields

- **Custom**, tick "License does not have a SPDX identifier".
- Name: `Blocky Music License`.
- URL: `https://qwu.world/mods/minecraft/blocky-music/license` — live since 19 September 2026.

## What is still on the owner

- **Workshop uploads must be open to everyone.** The service has an `uploads_not_open` gate ("Uploads open soon");
  while it is closed, the "In-game publishing" line promises something players cannot do.
- **A few published beatmaps in the Workshop**, so a first battle with a friend has something to play.
- **Gallery images** must be real screenshots from the game.
- Do not delete a version while the project is in review: that sends it to the back of the queue.
