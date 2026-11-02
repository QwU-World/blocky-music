# Blocky Music on CurseForge: the listing, ready to paste

Published on CurseForge on 2 November 2026: https://www.curseforge.com/minecraft/mc-mods/blocky-music

This is the CurseForge listing, field by field, for the first upload (0.2.0-beta.2). It is the Modrinth listing in
`MODRINTH-LISTING.md` adapted to CurseForge's rules, checked on 1 November 2026 against:

- Moderation Policies: https://support.curseforge.com/en/support/solutions/articles/9000197279-moderation-policies
- Creating and submitting a project:
  https://support.curseforge.com/en/support/solutions/articles/9000197241-creating-and-submitting-a-project
- Project submission guide and tips:
  https://support.curseforge.com/en/support/solutions/articles/9000199552-project-submission-guide-and-tips
- File processor errors: https://support.curseforge.com/en/support/solutions/articles/9000210425-curseforge-file-processor-errors-per-game
- Upload API (file fields): https://support.curseforge.com/en/support/solutions/articles/9000197321-curseforge-upload-api
- Mod Authors Terms: https://legal.overwolf.com/docs/curseforge/mod-authors-terms

What differs from Modrinth, and why:

- CurseForge has no disclosure switches, so what the mod sends and stores is a section of the description
  (**Network and files**). The Mod Authors Terms (3.1) ask authors to disclose the permissions a mod needs.
- Links to our website, forum, Discord and source go at the bottom of the description (Moderation Policies), and
  external download links are not allowed. The playerAnimator link points to its CurseForge page, because the
  CurseForge app installs required dependencies itself.
- The jar's own code starts no programs and the jar contains no native code; folders, links and the file picker
  open through Minecraft and the LWJGL library it ships, and the description says so. CurseForge's scanner refuses
  files that start PowerShell (a 2026 rejection read: "Your file cannot be accepted as it runs Powershell").
- The photosensitivity warning sits near the top, since there is no switch for it.
- The licence is a Custom licence with its full text: when licences differ between sites, CurseForge treats the one
  on its own page as authoritative.

---

## Name

```
Blocky Music
```

## Summary

```
Rhythm battles in Minecraft: four-lane note duels against players and mobs, beatmaps from an in-game Workshop, a DJ booth with speakers and cameras, and a beatmap editor.
```

If the form's counter does not take it, use the shorter one:

```
Four-lane rhythm battles against players and mobs, with an in-game beatmap Workshop, a DJ booth and an editor.
```

## Description

Switch the editor to **Markdown** first, then paste.

```markdown
Use the microphone on a player or mob, choose a beatmap from the Workshop, and let the battle begin!!!

> **Photosensitivity warning:** DJ booth effects and a beatmap's own effects can flash the whole screen in time with the music, faster than three times per second at high tempos. Spectators and players near a DJ booth can turn them off with "Spectator effects" in the mod's settings; players in a battle always see the chosen beatmap's effects.

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
- Java 21, Minecraft 1.21.1, NeoForge 21.1.219 or newer.
- [playerAnimator](https://www.curseforge.com/minecraft/mc-mods/playeranimator) by KosmX is required on the client; the CurseForge app installs it with the mod. Use version 2.0.4 or newer for Minecraft 1.21.1.
- No FFmpeg or other programs needed: the mod converts MP3, WAV, FLAC, M4A and AAC (Apple Lossless too), Opus, WebM, MKV and MPEG-TS songs to Ogg itself, and makes the 1.5x and 2x speed versions of your beatmaps the same way. Other formats, such as WMA, need converting with another program first.
- The Workshop sends nothing until you allow network access on its first-run screen, and you can turn access off again with the Network access switch on the Workshop's account page. Once allowed, it connects to blocky-music.qwu.world, loads account pictures from qwu.world, and contacts Mojang to sign you in with your Minecraft account and to show the skins of players who wrote server comments. It signs a Minecraft account in by itself, once per game launch, only if that account has already signed in to the Workshop on this computer, and stops when you sign out; if you have never signed in, nothing is sent for this. The privacy policy is linked at the bottom of this page.
- You must be at least 16 to upload, comment, or rate; browsing, downloading, and playing are open to everyone.
- This is a beta: there may be rough edges. Back up your worlds, report bugs and errors, and share your ideas on our forum (the links are at the bottom of this page).

## Network and files

The Workshop goes online only after you allow it:

- The Network access switch on the Workshop's account page turns it off again at any time; downloads in progress are then cancelled.
- blocky-music.qwu.world (QwU World) receives your IP address; the mod, Minecraft and NeoForge versions and your operating system's name; your searches; the beatmaps you install or check for updates; your Minecraft name and UUID when you sign in with Minecraft; and, after you sign in, your QwU World account token. Download counts and rate limits store only a shortened IP prefix.
- Automatic sign-in works only for a Minecraft account that has already signed in to the Workshop on this computer: once per game launch, when you open the Workshop, the mod sends its Minecraft name and UUID again (and once more a few seconds later if another login got in the way). Signing out stops it.
- Account pictures load only over https from qwu.world. Mojang's servers sign you in with your Minecraft account and show the skin heads of players who wrote server comments; the skin lookup at api.minecraftservices.com carries the same User-Agent (the mod, Minecraft and NeoForge versions and your system's name). Your Minecraft access token goes only to Mojang, in the same call Minecraft makes when you join a server, and never to QwU World.
- Workshop downloads are beatmap data only: charts, songs, preview clips, cover pictures and their descriptions. A beatmap package is checked by size and SHA-256 before it is installed. The mod never downloads or runs code.
- Outside the Workshop and its switch, the test NPC's settings screen looks a typed player name up at api.minecraftservices.com, with the same User-Agent, and loads that skin from Mojang to preview it.
- No analytics, no advertising, nothing sold. Without network access you can still install .blockymap files by drag-and-drop or from the inbox folder and play your own beatmaps; a Workshop beatmap needs an online check at least once a week before it can be brought into a battle, unless the server already has it.

On your computer:

- Songs are converted inside the game by the mod's own code. The mod's own code starts no programs, and the mod contains no native code.
- The file picker comes from LWJGL, which ships with Minecraft; on macOS and Linux LWJGL shows it through the system's osascript, zenity or kdialog. It opens when you import a .blockymap, pick the song for a Workshop beatmap published without one, choose a cover for an upload, or choose a skin file for the test NPC. "Open folder" and links work the way Minecraft opens folders and links, and links ask you first.
- Everything the mod saves, temporary files included, stays inside the Minecraft folder. It registers no file types, URL schemes or startup entries, and it does not use your microphone or camera: the "microphone" is an in-game item.

## License

- Code: MIT.
- Original graphics, models and animations: CC BY-NC 4.0.

Exceptions are listed individually on the license page, linked at the bottom of this page.

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT. The mod is not affiliated with ppy Pty Ltd (osu!) or The Funkin' Crew (Friday Night Funkin').

Contact: [it.qwuworld@gmail.com](mailto:it.qwuworld@gmail.com)

## How the mod is made

The QwU World team designs, oversees, and tests Blocky Music.

Since September 2026, an AI assistant has written and reviewed parts of the code and this text, always under the team's direction. Every feature was specified, tested in-game, and approved by people.

The icon was commissioned from an artist; the textures, models and animations were made without generative AI. Blocky Music ships no music; its one sound effect is a SampleFocus sample, credited on the license page.

## Links

- Mod page: [qwu.world/mods/minecraft/blocky-music](https://qwu.world/mods/minecraft/blocky-music)
- Workshop: [blocky-music.qwu.world](https://blocky-music.qwu.world)
- Forum: [qwu.world/forum](https://qwu.world/forum)
- Discord: [discord.gg/sSENQuz6a7](https://discord.gg/sSENQuz6a7)
- Source code: [github.com/QwU-World/blocky-music](https://github.com/QwU-World/blocky-music)
- Bug reports: [github.com/QwU-World/blocky-music/issues](https://github.com/QwU-World/blocky-music/issues)
- Privacy policy: [qwu.world/privacy](https://qwu.world/privacy#blocky-music-workshop)
- License: [qwu.world/mods/minecraft/blocky-music/license](https://qwu.world/mods/minecraft/blocky-music/license)
```

## Project fields

| Field | Value |
|---|---|
| Game | Minecraft |
| Class | Mods |
| Main category | Miscellaneous (CurseForge has no rhythm, music or minigame category) |
| Additional categories | Cosmetic, optional; leave the rest empty, unrelated categories get a project sent back |
| Logo | 400x400 PNG made from `src/main/resources/blocky_music_logo.png`; not WebP |
| Allow comments | On |
| Unlisted | Off |
| Third-party distribution | On, so launchers such as Prism can install it; downloads through them count neither toward total downloads nor toward Rewards. It can be changed later in the License tab |
| Social links | Discord `https://discord.gg/sSENQuz6a7` |
| Source (after creation) | `https://github.com/QwU-World/blocky-music` |
| Issues (after creation) | `https://github.com/QwU-World/blocky-music/issues` |

## License

Custom License, name:

```
Blocky Music License
```

Text:

```
Blocky Music License

QwU World's own code (Java source and classes, shaders, interface text, game data, font definitions, build scripts and tools): MIT License, reproduced below.
QwU World's own assets (textures, models, block states, Blockbench projects, player animations and sound definitions): Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC 4.0), https://creativecommons.org/licenses/by-nc/4.0/legalcode
Third-party material keeps its own licence. Every exception is listed file by file in META-INF/NOTICE.md inside the mod jar.
"QwU World", "Blocky Music" and their logos are not covered by either licence.
Full licence page: https://qwu.world/mods/minecraft/blocky-music/license

MIT License

Copyright (c) 2026 QwU World

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## File

| Field | Value |
|---|---|
| File | `blocky_music-0.2.0-beta.2.jar`, the same file uploaded to Modrinth (sha256 `de9b03999192b2d30aa13c7096286378f647e26daa63c98a5af7b19aec3f7172`) |
| Display name | `Blocky Music 0.2.0-beta.2` |
| Release type | Beta. CurseForge's articles disagree on whether a project with only Beta files reaches every app user; the type can be changed later on the file's page |
| Game version | 1.21.1 only |
| Mod loader | NeoForge only |
| Environment | Client and Server |
| Java | Java 21 |
| Related project | playerAnimator by KosmX (project 658587), Required Dependency |
| Release | Automatically after approval |

Changelog (Markdown):

```markdown
First file on CurseForge. Changes since 0.1.0-beta.1:

**Battles**

- When you challenge a player or a mob, you choose whether you are Player 1 or Player 2.
- Players and humanoid mobs hold a microphone during a battle.
- The battle camera is lower and turns smoothly, and when the parts are played one after the other it turns to whoever is playing. A camera block can now follow the battle's players.
- Pausing a single-player game pauses the battle: the bot stays in time, nothing runs on in the background, the song stays on its beatmap, and holds you keep pressed are not let go.
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

- Ctrl+A selects the page, Ctrl+drag selects a rectangle, Ctrl+V pastes into free space after the last copy and on the grid, and Ctrl+Z also undoes selections. On a Mac both Ctrl and Cmd work.
- Notes can be placed one after another, clicking a note's edge no longer deletes it, and right-clicking a hold turns it back into a normal note.
- The grid switch follows the timing, and the offset setting is clearer.
- The unsaved-changes warning shows above the editor, and English button labels fit.
- The game preview opened from the title screen has a background colour of your choice, and the FX preview with the World background shows the world.
- Messages are in your language, without "OK:" or "ERR:" in front, and no message or box shows the folders above your Minecraft folder or your user folder's name, also when a folder name has a space in it: after a save, the song and picture boxes show where the files are inside the Minecraft folder.

**Songs**

- Nothing to install: the mod itself converts MP3, WAV, FLAC (also as .oga), M4A, AAC, Apple Lossless, Opus, WebM, MKV and MPEG-TS (.ts files and HLS downloads) to Ogg, in the editor, for imported osu! beatmaps and for the 1.5x and 2x copies of a song. FFmpeg is no longer needed.
- Loud songs no longer distort or clip in the 1.5x and 2x copies.
- A battle that starts with the game, the Players sound slider or the mod's music muted plays its song as soon as you turn the volume up.

**Workshop**

- The upload window in the game works like the website's upload page, and its preview plays from the point you choose.
- The Workshop button is in the Esc menu on a server too, and the BM button is back on the title screen.
- Server beatmaps show their covers, and the server page opens with its comments and players' avatars. A server beatmap's page shows its own BPM, length and description, and every comment appears once.
- Cards keep every badge, text fits down to 320x240 in both languages, and the Published tab shows each beatmap's icon.
- A preview that cannot be loaded or played says so instead of spinning.
- The first-run screen says exactly what is sent and where: the hosts, the versions and system name, your Minecraft name and UUID at sign-in, and Mojang's part, also for the skin heads of server-comment authors. Because it changed, it asks once more, also if you answered it before.
- Network access can be turned off again at any time with the Network access switch on the account page.
- The Workshop signs you in automatically only with a Minecraft account that has signed in to the Workshop on this computer before, and stops after you sign out. If you never signed in, nothing is sent for this.
- Account pictures load only from qwu.world.
- A Workshop beatmap published without its song takes an MP3, FLAC, WAV, M4A or any other song file the mod reads, and the mod converts it to Ogg itself.
- The age message no longer says the whole Workshop is 16+: only uploading, commenting and rating are; browsing, downloading and playing are open to everyone.

**DJ booth**

- Add Workshop beatmaps to the booth with "+": they are downloaded for everyone, play like server beatmaps and are still there after a server restart. The booth's list holds only what you added, and × removes a beatmap.
- The booth keeps playing when you fly away and come back.
- Smiley balls look at a booth while it plays.
- Reset speakers no longer touches Visual FX. The panel keeps both columns on small windows and its mixer scrolls.

**Look and effects**

- The battle time bar, calibration, settings, DJ panel, camera, speaker, camera gallery, visual FX list and test NPC screens use the mod's purple look. Tips stay off the control they explain.
- "Panel Background" in the settings has a working preview. How dark the world gets in a battle can be set (0% is none), and the black bars can be turned off or made see-through.
- Camera setup hints are never covered by effects, and the numbers in the effect settings line up.
- A spectator's panels above the fighters stay above an effect that does not cover the interface, as your own panels do.
- Block outline, fish-eye and pulse work with Iris shader packs. Entity and block outlines reach only as far as the music can be heard and fade out near the edge.
- English says "beatmap" everywhere; Russian says «карта» and «песня» and speaks to you as «ты».

**Test NPC**

- A skin from a file as well as by name; pushing works with its AI off; its votes for random modes and its invulnerability work, and its random modes count in a battle you set up between two mobs too, where the chat says which side, Player 1 or Player 2, rolled what.

**Mod list**

- Blocky Music shows its logo, website and licence link, with its description in your language.

Network protocol 69: the server and every player need this same build. 0.1.0-beta.1 cannot join a 0.2.0-beta.2 server, and the other way round.
```

## Note for the reviewers

Only for a support ticket, if the file waits in manual review for long or comes back with a question:

```
Blocky Music 0.2.0-beta.2: notes for the review.

Source: https://github.com/QwU-World/blocky-music (Minecraft 1.21.1, NeoForge). The jar is built from this source plus one licensed SampleFocus sound effect that is not in the public repository.

The mod's own code never calls ProcessBuilder or Runtime.exec, and the jar contains no native code or executables. Folders and links open only through Minecraft's own Util.getPlatform().openPath and ConfirmLinkScreen, and files are chosen through LWJGL's TinyFileDialogs, which ships with Minecraft.

The only class that reads the Minecraft access token is ru.gora.musicbattle.client.workshop.MinecraftProof. It passes the token straight to authlib's MinecraftSessionService.joinServer, the same call vanilla Minecraft makes to join a server, so that Mojang's session server can confirm the player owns the account to our own service, blocky-music.qwu.world. The token is not stored and is never sent to QwU World.

The Workshop's network access is off until the player allows it on a first-run screen, and it can be turned off again. Separately, the test NPC's settings screen asks Mojang (api.minecraftservices.com) for the skin of a player name typed there. Workshop downloads are data only: chart JSON, Ogg audio and preview clips, PNG or JPEG covers and account pictures, and the beatmap's description JSON. Packages are checked by size and SHA-256, and nothing downloaded is loaded as code.

The jar contains no music. Songs come only from the player's own files or from community uploads to our Workshop, which has upload rules (https://blocky-music.qwu.world/rules/upload), a 16+ age limit for uploading and a copyright takedown process (https://blocky-music.qwu.world/legal/copyright). The Workshop hosts beatmap data, not mod files.

Reflection: ru.gora.musicbattle.network.ClientPayloadBridge uses Class.forName only for the mod's own client classes, so that dedicated servers never load them. ClientModelGlowEngine and DjCameraPreviewRenderer look up the optional Iris API (net.irisshaders.iris.api.v0.IrisApi) by name, and ClientModelGlowEngine calls a public getOriginal() on other mods' buffer-source wrappers to reach Minecraft's own one. UiTooltip reads a private tooltip-position field of Minecraft.

Privacy notice: https://qwu.world/privacy#blocky-music-workshop
```

## What is still on the owner

- Real in-game screenshots for the gallery: the same five as on Modrinth.
- Watch https://www.curseforge.com/my-notifications after submitting. On "Changes Required", edit the page and
  resubmit; never delete and re-upload.
- The Rewards Program is optional and only for one individual aged 18 or older; opting in covers all of that
  person's projects. A project earns points only if its Owner is enrolled, and points can be split among project
  members if that is set before they are paid out.
- Submitting means accepting the Mod Authors Terms (https://legal.overwolf.com/docs/curseforge/mod-authors-terms):
  the account holder is of legal age and holds the rights to everything uploaded.
