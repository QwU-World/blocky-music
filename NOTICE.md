# Blocky Music: licence notice

Blocky Music © 2026 QwU World · https://qwu.world · it.qwuworld@gmail.com
Last updated: 24 October 2026

Together, the terms in this notice make up the **Blocky Music License** (on Modrinth:
`LicenseRef-Blocky-Music-License`). It is not a separate licence text. QwU World's own code is under the
MIT License ([LICENSE](LICENSE)), QwU World's own assets are under CC BY-NC 4.0
([LICENSE-ASSETS.md](LICENSE-ASSETS.md)), and the exceptions are listed below. In the mod jar
(`META-INF/neoforge.mods.toml`) this is written as the SPDX expression `MIT AND CC-BY-NC-4.0`. That
expression is a one-line summary for the whole jar and cannot list exceptions, so the exceptions below
still apply. The build copies this file, `LICENSE` and `LICENSE-ASSETS.md` into the jar's `META-INF/`
folder.

In the lists below, code paths are relative to `src/main/java/ru/gora/musicbattle/` and asset paths are
relative to `src/main/resources/assets/music_battle/` (`assets/music_battle/` inside the jar). Files
described as being "in the repository root" are in the source repository only, not in the jar.

## Licences at a glance

| What | Licence | Where |
|---|---|---|
| QwU World's own code: Java source and compiled classes; shaders in `shaders/`; interface text in `lang/`; font definitions `font/ui_*.json`; `src/main/resources/data/`; `META-INF/neoforge.mods.toml`, `music_battle.mixins.json`, `pack.mcmeta`; build scripts, `tools/` and documentation. The rows below list the exceptions. | MIT | [LICENSE](LICENSE) |
| QwU World's own assets: `textures/`, `models/`, `blockstates/`, `blockbench/`, `player_animations/`, `sounds/`, `sounds.json`; in the repository root, `microphone.bbmodel` and its texture `texture.png` (copies of `blockbench/microphone.bbmodel` and `textures/item/microphone.png`) and `player anims.bbmodel` (the Blockbench file of the player animations). `textures/item/music_link.png` is drawn by `tools/make-music-link-texture.py` in this repository, so it is QwU World's own work like the rest of this row. The rows below list the exceptions. | CC BY-NC 4.0 | [LICENSE-ASSETS.md](LICENSE-ASSETS.md) |
| Parts of code that follow the sources under **Credits: public domain** | Public domain, as their authors state | Below |
| Parts of `build.gradle`, `settings.gradle`, `gradle.properties` and `META-INF/neoforge.mods.toml` that come from the NeoForge MDK template | MIT, from the NeoForged project (not from QwU World) | **Third-party material** below |
| The Nunito fonts `font/nunito-semibold.ttf`, `font/nunito-bold.ttf`, `font/nunito-extrabold.ttf` (modified) | SIL Open Font License 1.1, from The Nunito Project Authors (not from QwU World) | [`font/license-ofl.txt`](src/main/resources/assets/music_battle/font/license-ofl.txt), **Third-party material** below |
| The Lucide icons in `textures/gui/workshop/icons_16.png`, `icons_32.png`, `icons_64.png` | ISC, from Lucide Contributors; the icons derived from Feather also MIT, from Cole Bemis (not from QwU World) | [`textures/gui/workshop/lucide-license.txt`](src/main/resources/assets/music_battle/textures/gui/workshop/lucide-license.txt), **Third-party material** below |
| Parts of `client/ui/Motion.java` that follow osu-framework | MIT, from ppy Pty Ltd (not from QwU World) | **Third-party material** below |
| The Minecraft expression in `mixin/client/ChannelMixin.java`, the Minecraft model boilerplate, `sounds/entity/smiley_damage.ogg`, and everything under **Pending confirmation** | Neither of QwU World's licences | Below |

The `LICENSE` file is the standard MIT text. It applies only to the files in the MIT row above.

## Names and logos

"QwU World", "Blocky Music" and their logos are not covered by either licence. The licences give no right
to use them. Please do not use them for your own releases, or in any way that suggests QwU World made or
endorsed your work, without asking us first.

## Music and player content

- Blocky Music ships no music.
- Charts, audio, cover images, osu! beatmaps and Friday Night Funkin' charts that a player creates, imports
  or shares belong to their owners. This includes tracks published to a world or server. QwU World does
  not license them and grants no rights to them.
- A player who imports or shares a track is responsible for having the right to use and share it.

## Other products named in the mod

Blocky Music can import charts in the Friday Night Funkin' JSON format and osu! `.osu` and `.osz` files.
These names only identify the file formats. **Blocky Music is not affiliated with or endorsed by The
Funkin' Crew Inc. (Friday Night Funkin') or ppy Pty Ltd (osu!).** "Friday Night Funkin'" is a trademark of
The Funkin' Crew Inc. ([source](https://github.com/FunkinCrew/Funkin/blob/main/LICENSE.md)). ppy states
that the "osu!" and "ppy" branding is protected by trademark law
([source](https://github.com/ppy/osu/blob/master/README.md)). The mod also names the Iris and Oculus shader
mods, only to explain a compatibility limit.

## Minecraft

> NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT

(Disclaimer wording from the [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines).)

QwU World, not Mojang or Microsoft, is responsible for Blocky Music. Contact: it.qwuworld@gmail.com.

## Not included in Blocky Music

- **playerAnimator** by KosmX: a required client-side mod that players install separately
  ([Modrinth](https://modrinth.com/mod/playeranimator)). It has its own MIT licence, "Copyright (c) 2022
  KosmX" ([source](https://github.com/KosmX/minecraftPlayerAnimator/blob/1.21/LICENSE)). It is not bundled
  in the Blocky Music jar and is not covered by QwU World's licences.
- **Minecraft and NeoForge** are not included. Audio is decoded with the Ogg Vorbis decoder that ships
  with Minecraft.
- **FFmpeg.** Blocky Music runs FFmpeg as a separate program — the one already installed on the player's or
  the server operator's own computer — to convert audio to Ogg Vorbis and to render the sped-up copies of a
  track. It is not bundled with the mod and the mod never downloads it; browsing, installing and playing need
  no FFmpeg at all, and the mod says plainly when there is none to run. FFmpeg is its own project under its
  own licence ([source](https://ffmpeg.org/legal.html)) and neither of QwU World's licences applies to it.

## Credits: public domain

The files named below are under MIT ([LICENSE](LICENSE)); that licence covers QwU World's own code in them.
Parts of them follow the sources listed here. **Their authors state that these sources are public
domain**, and each author's own statement is quoted. QwU World does not claim those parts as its own work.
They are credited here:

- **Reverb** in `client/LiveDjAudioEffects.java` uses the filter structure and tuning values of Freeverb
  by Jezar at Dreampoint (June 2000). The Freeverb source headers say "This code is public domain" (read
  from a [mirror](https://github.com/alexmacrae/SamplerBox/tree/master/freeverb); see also
  [CCRMA](https://ccrma.stanford.edu/~jos/pasp/Freeverb.html)).
- **Integer hash** in `client/ClientTrackEffects.java` and `client/ClientOsuManiaImporter.java`: lowbias32
  by Chris Wellons ([source](https://nullprogram.com/blog/2018/07/31/): "All information on this blog,
  unless otherwise noted, is hereby released into the public domain").
- **Depth packing** in the shaders `shaders/core/object_depth_copy.fsh` and `shaders/core/object_outline.fsh`:
  a three-channel form of EncodeFloatRGBA by Aras Pranckevičius
  ([source](https://aras-p.info/blog/2009/07/30/encoding-floats-to-rgba-the-final/): "Code snippets are
  public domain, unless specified otherwise").

## Third-party material

QwU World does not offer the material in this section under its licences.

**NeoForge MDK template (MIT).** `build.gradle`, `settings.gradle`, `gradle.properties` and
`src/main/resources/META-INF/neoforge.mods.toml` are based on the NeoForge MDK template for Minecraft
1.21.1 ([source](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)). QwU World's MIT licence covers
only QwU World's changes to these files. Of them, only `META-INF/neoforge.mods.toml` is in the jar. It has
not been confirmed that this functional boilerplate is a "substantial portion" of the template, which is
when MIT's notice condition applies; the template's notice is included here as a precaution, not as a
statement that the law requires it. The template's licence
([TEMPLATE_LICENSE.txt](https://raw.githubusercontent.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle/main/TEMPLATE_LICENSE.txt))
reads:

```text
MIT License

Copyright (c) 2023 NeoForged project

This license applies to the template files as supplied by github.com/NeoForged/MDK


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

**Nunito fonts (SIL Open Font License 1.1).** `font/nunito-semibold.ttf`, `font/nunito-bold.ttf` and
`font/nunito-extrabold.ttf` are built by `tools/fonts/build-fonts.py` from `Nunito[wght].ttf` in Google Fonts
(`github.com/google/fonts`, `ofl/nunito`, SHA-256 pinned in the script). "Copyright 2014 The Nunito Project
Authors (https://github.com/googlefonts/nunito)". They are **Modified Versions** under the OFL: the weights 600,
700 and 800 are instanced into static fonts, the character set is subset (Latin, Latin-1, Latin Extended-A,
Cyrillic, punctuation), hinting and OpenType layout tables are removed, and simple outlines are added for
← ↑ → ↓ ★ ♥. Nunito has no Reserved Font Name. The fonts are distributed only inside the mod, never sold by
themselves, and remain under the OFL, whose full text is in `font/license-ofl.txt` next to them. That name is
lowercase because Minecraft rejects a resource path containing an uppercase letter: while the file was called
`OFL.txt`, every player's log carried `Invalid path in pack: music_battle:font/OFL.txt, ignoring`. Neither of QwU
World's licences applies to them.

**Lucide icons (ISC).** The icon atlases `textures/gui/workshop/icons_16.png`, `icons_32.png` and `icons_64.png`
are rasterised by `tools/icons/build-icons.mjs` from `lucide-static` 1.47.0 (npm, integrity pinned in the script):
"Copyright (c) 2026 Lucide Icons and Contributors", ISC License. Lucide states that some of its icons derive from
Feather, "Copyright (c) 2013-present Cole Bemis", MIT License; of the icons used here that applies to
arrow-left, check, chevron-down, chevron-left, chevron-right, download, external-link, info, link, music, search,
trash-2, triangle-alert (Feather's alert-triangle), upload and x. Both licence texts, as Lucide ships them, are in
`textures/gui/workshop/lucide-license.txt`
next to the atlases. The icons are recoloured to white and drawn tinted. Neither of QwU World's licences applies
to them.

**osu-framework (MIT).** `client/ui/Motion.java` follows osu-framework 2026.914.0 by ppy Pty Ltd
([source](https://github.com/ppy/osu-framework/tree/2026.914.0)): the easing curves of
`DefaultEasingFunction` and the scroll model and constants of `ScrollContainer`. QwU World's MIT licence
covers only QwU World's own code in that file. osu-framework's licence
([LICENCE](https://raw.githubusercontent.com/ppy/osu-framework/2026.914.0/LICENCE)) reads:

```text
Copyright (c) 2024 ppy Pty Ltd <contact@ppy.sh>.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

**Rounded-box distance formula.** The rounded-rectangle distance in `shaders/core/ui_sdf.fsh`,
`shaders/core/ui_rounded_image.fsh` and `client/ui/SdfMath.java` is the exact rounded-box distance function
published by Inigo Quilez ([article](https://iquilezles.org/articles/distfunctions2d/), "Rounded Box - exact").
It is a short mathematical formula, credited here and not claimed as QwU World's work; the article states no
licence of its own.

**Mojang.** The material below is Minecraft's and is covered by the
[Minecraft EULA](https://www.minecraft.net/en-us/eula). Some entries are whole files, others are parts of
files. None of it is QwU World's work or licensed by QwU World:

- The seven effect shader programs in `shaders/core/` use Minecraft's own vertex shader by reference
  (`"vertex": "minecraft:blit_screen"`), and `shaders/core/ui_rounded_image.json` uses
  `"vertex": "minecraft:position_tex_color"` the same way. The game loads them from the player's own copy of
  Minecraft; they are not in the Blocky Music jar. (An earlier development build shipped a byte-identical copy
  of `blit_screen` as `fx_blit.vsh`; it has been removed.) `shaders/core/ui_sdf.vsh` is QwU World's own.
- `mixin/client/ChannelMixin.java` is QwU World's code under MIT, except for one expression: the fallback
  buffer-size calculation, which repeats Minecraft's `Channel.calculateBufferSize`. That expression is not
  QwU World's and is not licensed by it.
- Model boilerplate: `models/item/rap_god_config_wand.json` is identical to Minecraft 1.21.1's
  `assets/minecraft/models/item/blaze_rod.json` apart from a final newline, and
  `models/item/smiley_ball_spawn_egg.json` and `models/item/test_rap_god_master.json` have the same content
  as Minecraft's spawn-egg item models (`"parent": "minecraft:item/template_spawn_egg"`). These are
  standard format boilerplate and are not claimed as QwU World's work.

**SampleFocus sound.**

- `sounds/entity/smiley_damage.ogg` is a sound from SampleFocus (its metadata says "Downloaded from
  Samplefocus.com"). QwU World decided on 17 September 2026 to keep it rather than replace it. Blocky Music
  uses it as part of the mod under the SampleFocus Standard License (https://samplefocus.com/license), which
  allows a sample to be used "as part of a new creative work", including games. It is **not** covered by QwU
  World's MIT or CC BY-NC 4.0 licences, and they give no right to it. Do not extract it from the mod, and do
  not redistribute it by itself or in substantially the same form.

## Not in the public source repository

The public repository at https://github.com/QwU-World/blocky-music leaves out one file on purpose:
`sounds/entity/smiley_damage.ogg`, because the SampleFocus licence allows it inside the mod, not on its own. A build
from that source runs without it.

## Pending confirmation

Nothing is waiting for confirmation: every file in the jar and in the repository is accounted for above.
