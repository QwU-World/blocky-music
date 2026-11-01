# Blocky Music: licence notice

Blocky Music © 2026 QwU World · https://qwu.world · it.qwuworld@gmail.com
Last updated: 1 November 2026

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
| QwU World's own code: Java source and compiled classes; shaders in `shaders/`; interface text in `lang/`; font definitions `font/ui_*.json`; `src/main/resources/data/`; `META-INF/neoforge.mods.toml`, `music_battle.mixins.json`, `pack.mcmeta`; build scripts (not the Gradle wrapper), `tools/`, documentation, the `.github/` templates and `.gitignore`. The rows below list the exceptions. | MIT | [LICENSE](LICENSE) |
| QwU World's own assets: `textures/`, `models/`, `blockstates/`, `blockbench/`, `player_animations/` (made from `blockbench/bm_player_animation.bbmodel` by `tools/convert_blockbench_player_animations.mjs`), `sounds/`, `sounds.json`; in the repository root, `microphone.bbmodel` and its texture `texture.png` (copies of `blockbench/microphone.bbmodel` and `textures/item/microphone.png`) and `player anims.bbmodel` (an earlier Blockbench draft of the player animations, not used by the build; its six `ref_` cubes are only the standard Minecraft player shape the animations are posed against). `sounds/` holds no sound of QwU World's own: its one file is the SampleFocus sound in the last row, so the public source repository has no `sounds/` folder. `textures/item/music_link.png` is drawn by `tools/make-music-link-texture.py` in this repository, so it is QwU World's own work like the rest of this row. The rows below list the exceptions. | CC BY-NC 4.0 | [LICENSE-ASSETS.md](LICENSE-ASSETS.md) |
| Parts of code that follow the sources under **Credits: public domain** | Public domain, as their authors state | Below |
| Parts of `build.gradle`, `settings.gradle`, `gradle.properties` and `META-INF/neoforge.mods.toml` that come from the NeoForge MDK template | MIT, from the NeoForged project (not from QwU World) | **Third-party material** below |
| The Gradle wrapper `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` and `gradle/wrapper/gradle-wrapper.properties` (source repository only) | Apache License 2.0, from the Gradle authors (not from QwU World) | **Third-party material** below |
| The Nunito fonts `font/nunito-semibold.ttf`, `font/nunito-bold.ttf`, `font/nunito-extrabold.ttf` (modified) | SIL Open Font License 1.1, from The Nunito Project Authors (not from QwU World) | [`font/license-ofl.txt`](src/main/resources/assets/music_battle/font/license-ofl.txt), **Third-party material** below |
| The Lucide icons in `textures/gui/workshop/icons_16.png`, `icons_32.png`, `icons_64.png` | ISC, from Lucide Contributors; the icons derived from Feather also MIT, from Cole Bemis (not from QwU World) | [`textures/gui/workshop/lucide-license.txt`](src/main/resources/assets/music_battle/textures/gui/workshop/lucide-license.txt), **Third-party material** below |
| Parts of `client/ui/Motion.java` that follow osu-framework | MIT, from ppy Pty Ltd (not from QwU World) | **Third-party material** below |
| The lowpass-by-quality values in `audio/codec/VorbisEncoderLayout.java` that come from libvorbis, and the granule-position rule in `VorbisDecoder.queueMinecraft` that follows libvorbis | BSD-3-Clause, from the Xiph.Org Foundation (not from QwU World) | **Third-party material** below |
| The AAC tables and the parametric stereo processing in `audio/codec/AacHuffmanData.java`, `AacTables.java`, `AacSbrData.java`, `AacPsData.java`, `AacPs.java`, and the Apple Lossless decoder `audio/codec/AlacDecoder.java` | Apache License 2.0, from Apple Inc., The Android Open Source Project (Ittiam Systems), PacketVideo and VisualOn (not from QwU World) | **Third-party material** below |
| The Opus decoder in `audio/codec/OpusRangeDecoder.java`, `OpusCeltDecoder.java`, `OpusCeltBands.java`, `OpusCeltAllocation.java`, `OpusCeltMdct.java`, `OpusCeltTables.java`, `OpusSilkDecoder.java`, `OpusSilkChannel.java`, `OpusSilkResampler.java`, `OpusSilkTables.java`, `OpusPacketDecoder.java` and `OpusMultistreamDecoder.java` (a Java port of the libopus 1.6.1 decoder, its constant tables included) | BSD-3-Clause, from Xiph.Org, Skype Limited, CSIRO, Jean-Marc Valin, Timothy B. Terriberry, Gregory Maxwell, Mark Borgerding, Koen Vos, Arm Limited and the other contributors to libopus (not from QwU World) | **Third-party material** below |
| The Minecraft expression in `mixin/client/ChannelMixin.java`, the Minecraft model boilerplate, the SampleFocus sound `sounds/entity/smiley_damage.ogg` (in the jar, not in the public source repository) and its decoded copy `tools/vorbis-decoder-support/smiley_damage.libvorbis.f32` (in neither the jar nor the public source repository) | Neither of QwU World's licences | Below |

The `LICENSE` file is the standard MIT text. It applies only to the files in the MIT row above.

## Names and logos

"QwU World", "Blocky Music" and their logos are not covered by either licence. The licences give no right
to use them. Please do not use them for your own releases, or in any way that suggests QwU World made or
endorsed your work, without asking us first. The Blocky Music logo is `src/main/resources/blocky_music_logo.png`
(`blocky_music_logo.png` in the jar, the mod's icon in the mod list), commissioned from an artist.

## Music and player content

- Blocky Music ships no music.
- Beatmaps, songs and other audio, cover images, and osu! and Friday Night Funkin' files that a player creates,
  imports or shares belong to their owners. This includes beatmaps and songs published to a world or server. QwU
  World does not license them and grants no rights to them.
- A player who imports or shares a beatmap or song is responsible for having the right to use and share it.

## Other products named in the mod

Blocky Music can import beatmaps from Friday Night Funkin' JSON files and osu! `.osu` and `.osz` files.
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
- **Minecraft and NeoForge** are not included. Songs are played with the Ogg Vorbis decoder that ships with
  Minecraft. Converting a song to Ogg Vorbis, and making the 1.5x and 2x copies a battle plays, is done by
  the mod's own code in `audio/` and `audio/codec/`; the parts of it that follow other people's work are
  credited below.
- **No other programs.** Blocky Music does not include, download or run FFmpeg or any other program to
  convert audio.

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
- **Ogg Vorbis decoder** in `audio/codec/VorbisDecoder.java`, `VorbisDecoderSetup.java`,
  `VorbisDecoderBits.java`, `VorbisDecoderMdct.java` and `OggPageReader.java`: a Java port of stb_vorbis 1.22
  by Sean Barrett ([source](https://github.com/nothings/stb/blob/1ee679ca2ef753a528db5ba6801e1067b40481b8/stb_vorbis.c),
  commit 1ee679ca2ef753a528db5ba6801e1067b40481b8, the last one to change the file; it is unchanged at commit
  003501f6d7a8668ce8b6d875e34dd97ad0d5d5b5). stb_vorbis offers two licences ("This software is available under 2
  licenses -- choose whichever you prefer"); this port relies on the second, "ALTERNATIVE B - Public Domain
  (www.unlicense.org): This is free and unencumbered software released into the public domain." Where the port
  departs from stb_vorbis it follows the Vorbis I specification by the Xiph.Org Foundation, and the code says so
  at each place.
- **MP3 decoder** in `audio/codec/Mp3Decoder.java`: its frame decoder is a Java port of minimp3 by Lieff
  ([source](https://github.com/lieff/minimp3/tree/ea99364f61c14656440e8d77e9c233ccf3124633), commit
  ea99364f61c14656440e8d77e9c233ccf3124633). minimp3.h says: "To the extent possible under law, the author(s) have
  dedicated all copyright and related and neighboring rights to this software to the public domain worldwide.
  This software is distributed without any warranty." (Creative Commons CC0 1.0.)

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

**Gradle wrapper (Apache License 2.0).** `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` and
`gradle/wrapper/gradle-wrapper.properties` are the Gradle wrapper, generated by Gradle: "Copyright © 2015 the
original authors", Apache License 2.0
(`SPDX-License-Identifier: Apache-2.0` in `gradlew`), with the licence text in the jar's own `META-INF/LICENSE`.
They are files of the source repository only, not of the mod jar. QwU World's MIT licence does not cover them. The
Apache License 2.0 is reproduced in full below, under **AAC and Apple Lossless decoding**.

**Nunito fonts (SIL Open Font License 1.1).** `font/nunito-semibold.ttf`, `font/nunito-bold.ttf` and
`font/nunito-extrabold.ttf` are built by `tools/fonts/build-fonts.py` from `Nunito[wght].ttf` in Google Fonts
(`github.com/google/fonts`, `ofl/nunito`, SHA-256 pinned in the script). "Copyright 2014 The Nunito Project
Authors (https://github.com/googlefonts/nunito)". They are **Modified Versions** under the OFL: the weights 600,
700 and 800 are instanced into static fonts, the character set is subset (Latin, Latin-1, Latin Extended-A,
Cyrillic, punctuation), hinting and OpenType layout tables are removed, and simple outlines are added for
← ↑ → ↓ ★ ♥. Nunito has no Reserved Font Name. The fonts are distributed only inside the mod and its source
repository, never sold by themselves, and remain under the OFL, whose full text is in `font/license-ofl.txt` next
to them. That name is lowercase because Minecraft rejects a resource path containing an uppercase letter: while the file was called
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

**libvorbis (BSD-3-Clause).** The rest of the mod's audio code is QwU World's own, written from the Vorbis I
specification, the FLAC specification (RFC 9639), ISO/IEC 14496-3 (MPEG-4 audio, for AAC), ISO/IEC 14496-12 (the
ISO base media file format, for MP4), RFC 6716 and RFC 7845 (Opus), RFC 9559 (Matroska), ISO/IEC 13818-1 (MPEG
transport streams, `audio/codec/MpegTsDecoder.java`), ISO/IEC 11172-3 (free-format MP3), the Opus project's
specifications for Opus in ISO BMFF and in MPEG-2 transport streams, the IMA's "Recommended Practices for Enhancing
Digital Audio Compatibility in Multimedia Systems" (1992, IMA ADPCM) and Microsoft's "Multimedia Standards Update"
(1994, Microsoft ADPCM and RIFX; `audio/codec/WavAdpcm.java`, `WavDecoder.java`) and published methods, except for
the code the entries after this one name (AAC, Apple Lossless, Opus) and two exceptions here. (The default Opus
channel mappings for one to eight channels in `MpegTsDecoder.java` are that specification's table, the same numbers
as libopus's `vorbis_mappings` in `src/opus_multistream_encoder.c`, which the libopus notice below covers.) First,
the lowpass frequency for each encoding quality in `audio/codec/VorbisEncoderLayout.java` (`lowpassForQuality`)
uses the twelve values of `_psy_lowpass_44` in libvorbis's `lib/modes/psych_44.h` (commit
4ba8efed8797016a85e57bc3cc7975bb31d65db5 of [libvorbis](https://github.com/xiph/vorbis)) by the Xiph.Org
Foundation. Second, on Minecraft's timeline `VorbisDecoder.queueMinecraft` ends a song by libvorbis's own rule
from `vorbis_synthesis_blockin` (`lib/block.c`): the granule position is counted from the first one a packet
carries, by the distance between block centres, and at the end of the stream the samples counted past the page's
granule position are cut. Minecraft decodes with JOrbis, a Java port of libvorbis that keeps this rule, and the
mod has to follow it to stay in step with the game; its handful of lines follow the rule statement for statement.
It has not been confirmed that twelve numbers, or a rule this short that the behaviour dictates, are protected at
all; libvorbis's licence is reproduced here as a precaution, as its condition for redistribution in binary form
asks, not as a statement that the law requires it. QwU World's MIT licence does not cover those values and those
lines. The licence (`COPYING` in libvorbis 1.3.7) reads:

```text
Copyright (c) 2002-2020 Xiph.org Foundation

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

- Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.

- Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.

- Neither the name of the Xiph.org Foundation nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE FOUNDATION
OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

**AAC and Apple Lossless decoding (Apache License 2.0).** `audio/codec/AlacDecoder.java` is a Java port of
Apple's reference Apple Lossless decoder: `codec/ALACDecoder.cpp`, `ag_dec.c`, `dp_dec.c`, `matrix_dec.c`,
`ALACBitUtilities.c` and `aglib.h` of [alac](https://github.com/macosforge/alac/tree/c38887c5c5e64a4b31108733bd79ca9b2496d987)
(commit c38887c5c5e64a4b31108733bd79ca9b2496d987), "Copyright (c) 2011 Apple Inc. All rights reserved."
`audio/codec/AacPs.java` is a Java port of the floating-point parametric stereo decoder in Ittiam's libxaac
(`decoder/ixheaacd_ps_dec_flt.c` of [libxaac](https://github.com/ittiam-systems/libxaac/tree/2fbadd57bca46693b7a077bcf1513131106d960c),
commit 2fbadd57bca46693b7a077bcf1513131106d960c), and the constant tables in `audio/codec/AacPsData.java` and
`AacSbrData.java` come from the same commit (`decoder/ixheaacd_sbr_rom.c`, `encoder/ixheaace_sbr_rom.c`,
`common/ixheaac_esbr_rom.c`): "Copyright (C) 2018 The Android Open Source Project" and "Copyright (C) 2023 The
Android Open Source Project", "Originally developed and contributed by Ittiam Systems Pvt. Ltd, Bangalore". The fine
IID Huffman codes in `AacSbrData.java` and the scale factor band and TNS tables in `audio/codec/AacTables.java` come
from PacketVideo's OpenCORE AAC decoder (`ps_read_data.cpp`, `sfb.cpp`, `get_tns.cpp` in the Android Open Source
Project's `platform/frameworks/base` at tag android-4.0.4_r1, `media/libstagefright/codecs/aacdec`), "Copyright (C)
1998-2009 PacketVideo". The Huffman codebooks in `audio/codec/AacHuffmanData.java` come from VisualOn's AAC encoder
(`src/aac_rom.c` at the same tag, `media/libstagefright/codecs/aacenc`), "Copyright 2003-2010, VisualOn, Inc.". The rest
of the AAC, SBR and MP4 code is QwU World's own, written from ISO/IEC 14496-3 and ISO/IEC 14496-12. The ported files
are changed from their sources: they are Java, every read is checked against the packet, and each file says where it
departs from its source and why. QwU World's MIT licence does not cover those ports and tables. The licences give no
right to patents other than the contributors' own (Apache License 2.0, section 3). libxaac's `NOTICE` file reads:

```text
/******************************************************************************
 *                                                                            *
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *****************************************************************************
 * Originally developed and contributed by Ittiam Systems Pvt. Ltd, Bangalore
*/
```

The Apache License 2.0, under which all four sources are offered, reads:

```text

                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS
```

**libopus (BSD-3-Clause).** The Opus decoder in `audio/codec/` is a Java port of the decoder in libopus 1.6.1, its
floating-point build ([source](https://github.com/xiph/opus/tree/22244de5a79bd1d6d623c32e72bf1954b56235be), tag
v1.6.1, commit 22244de5a79bd1d6d623c32e72bf1954b56235be): `OpusRangeDecoder.java` follows `celt/entdec.c`,
`celt/entcode.c` and `celt/laplace.c`, with the constants of `celt/mfrngcod.h` and `celt/ecintrin.h`;
`OpusCeltDecoder.java`, `OpusCeltBands.java`, `OpusCeltAllocation.java`,
`OpusCeltMdct.java` and `OpusCeltTables.java` follow `celt/celt_decoder.c`, `celt.c`, `bands.c`, `vq.c`, `cwrs.c`,
`rate.c`, `quant_bands.c`, `mdct.c`, `kiss_fft.c`, `pitch.c`, `celt_lpc.c`, `mathops.c`, `modes.c` and
`static_modes_float.h`; `OpusSilkDecoder.java`, `OpusSilkChannel.java`, `OpusSilkResampler.java` and
`OpusSilkTables.java` follow the SILK decoder in `silk/` (`dec_API.c`, `decode_frame.c`, `decode_indices.c`,
`decode_pulses.c`, `decode_parameters.c`, `decode_pitch.c`, `decode_core.c`, `PLC.c`, `CNG.c`, `NLSF_decode.c`,
`NLSF2A.c`, `stereo_decode_pred.c`, `stereo_MS_to_LR.c`, `resampler*.c` and the `tables_*.c` files, among others);
`OpusPacketDecoder.java` follows `src/opus_decoder.c` and `src/opus.c`, with the constants of
`include/opus_defines.h` and `src/opus_private.h`; `OpusMultistreamDecoder.java` follows
`src/opus_multistream_decoder.c` and `src/opus_multistream.c`. The constant tables are copied value for value. The
ported files are changed from their sources: they are Java, every read is checked against the packet, and the
machine-learning extensions (DRED, deep PLC, OSCE) and the QEXT extension are left out. The Ogg Opus and WebM readers
(`OpusDecoder.java`, `WebmDecoder.java`, `OpusPcmStream.java`, `OpusHead.java`) are QwU World's own, written from
RFC 7845 and RFC 9559. QwU World's MIT licence does not cover the ports and tables. The ported source files and the
headers whose definitions they use carry these notices:

```text
Copyright (c) 2001-2008 Timothy B. Terriberry
Copyright (c) 2001-2011 Timothy B. Terriberry
Copyright (c) 2002-2008 Jean-Marc Valin
Copyright (c) 2003-2004, Mark Borgerding
Copyright (c) 2003-2008 Jean-Marc Valin
Copyright (c) 2003-2008 Timothy B. Terriberry
Copyright (c) 2005-2007, Xiph.Org Foundation
Copyright (c) 2006-2011, Skype Limited. All rights reserved.
Copyright (c) 2007 CSIRO
Copyright (c) 2007-2008 CSIRO
Copyright (c) 2007-2008 Xiph.Org Foundation
Copyright (c) 2007-2009 Timothy B. Terriberry
Copyright (c) 2007-2009 Xiph.Org Foundation
Copyright (c) 2007-2010 Xiph.Org Foundation
Copyright (c) 2008 Gregory Maxwell
Copyright (c) 2008 Xiph.Org Foundation
Copyright (c) 2008, Xiph.Org Foundation, CSIRO
Copyright (c) 2008-2009 Gregory Maxwell
Copyright (c) 2008-2009 Xiph.Org Foundation
Copyright (c) 2009-2010 Xiph.Org Foundation
Copyright (c) 2010 Xiph.Org Foundation, Skype Limited
Copyright (c) 2010-2011 Xiph.Org Foundation, Skype Limited
Copyright (c) 2011 Xiph.Org Foundation
Copyright (c) 2011 Xiph.Org Foundation, Skype Limited
Copyright (c) 2012 Xiph.Org Foundation
Copyright (c) 2013, Koen Vos. All rights reserved.
Copyright (c) 2024 Arm Limited
```

The CELT, `src/` and `include/` files are under the two-clause form of the licence below (without its third
condition), the SILK files and libopus as a whole under the three-clause form. libopus's `COPYING` reads:

```text
Copyright 2001-2023 Xiph.Org, Skype Limited, Octasic,
                    Jean-Marc Valin, Timothy B. Terriberry,
                    CSIRO, Gregory Maxwell, Mark Borgerding,
                    Erik de Castro Lopo, Mozilla, Amazon

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

- Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.

- Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.

- Neither the name of Internet Society, IETF or IETF Trust, nor the
names of specific contributors, may be used to endorse or promote
products derived from this software without specific prior written
permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

Opus is subject to the royalty-free patent licenses which are
specified at:

Xiph.Org Foundation:
https://datatracker.ietf.org/ipr/1524/

Microsoft Corporation:
https://datatracker.ietf.org/ipr/1914/

Broadcom Corporation:
https://datatracker.ietf.org/ipr/1526/
```

**Rounded-box distance formula.** The rounded-rectangle distance in `shaders/core/ui_sdf.fsh`,
`shaders/core/ui_rounded_image.fsh` and `client/ui/SdfMath.java` is the exact rounded-box distance function
published by Inigo Quilez ([article](https://iquilezles.org/articles/distfunctions2d/), "Rounded Box - exact").
It is a short mathematical formula, credited here and not claimed as QwU World's work; the article states no
licence of its own.

**Mojang.** The material below is Minecraft's and is covered by the
[Minecraft EULA](https://www.minecraft.net/en-us/eula). Some entries are whole files, others are parts of
files. None of it is QwU World's work or licensed by QwU World:

- The six effect shader programs in `shaders/core/` use Minecraft's own vertex shader by reference
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
- `tools/vorbis-decoder-support/smiley_damage.libvorbis.f32` is the same sound decoded to raw samples by
  libvorbis. It is in neither the jar nor the public source repository: QwU World keeps it only in its private
  working copy, where the `native-vorbis-decoder` check compares the mod's own decoder with it. It is the same
  sound in another form, so the same terms apply to it, and the `tools/` row of the MIT licence does not cover it.

## Not in the public source repository

The public repository at https://github.com/QwU-World/blocky-music leaves out two files on purpose:
`sounds/entity/smiley_damage.ogg`, because the SampleFocus licence allows it inside the mod, not on its own, and
`tools/vorbis-decoder-support/smiley_damage.libvorbis.f32`, the same sound decoded for a check. A build from that
source runs without them.

## Pending confirmation

Nothing is waiting for confirmation: every file in the jar and in the repository is accounted for above.
