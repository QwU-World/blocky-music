# How the audio test fixtures were made

The decoder checks read short audio files from `tools/fixtures/` and `tools/vorbis-decoder-support/`. None of them is
recorded or copied audio, and none is made from the mod's SampleFocus sound. The signals are computed from formulas
(sweeps, tones, chords, clicks, seeded noise, silence) by the generators in this folder, then encoded with ordinary
encoders. The chart fixtures in `tools/fixtures/charts/` are small beatmaps written by hand for the `chart-offset` and
`dj-camera-alpha` checks; they have no song.

This folder holds the generators and one script that runs the recipes:

```sh
sh tools/fixtures/gen/make-fixtures.sh all                # or aac-alac, extras, opus; output in build/fixture-gen
LIBOPUS_SRC=/path/to/opus-1.6.1 sh tools/fixtures/gen/make-fixtures.sh opus
```

The script writes the files into `build/fixture-gen` (or the folder given as the second argument), never into
`tools/fixtures`, and then compares each one with the committed file. It needs a JDK 21 (`BLOCKY_JDK` or `JAVA_HOME`,
otherwise `java` on PATH), python3, ffmpeg 9 with libmp3lame and libopus, LAME, and macOS's `afconvert` for the Apple
encoders. `mode-switch.opus` also needs a C compiler, the libopus 1.6.1 source (`LIBOPUS_SRC`) and a compiled mod
(`gradle compileJava`), because ContainerLab uses the mod's Opus classes.

| File | What it is |
| --- | --- |
| `FixtureSignal.java` | The signal of the AAC, ALAC and extras fixtures: different sweeps and tones per channel, identical channels with clicks, noise with an anti-phase channel, silence and drum-like hits, cycling every 1.6 s. Only `java.util.Random` and `StrictMath`, so the samples are the same on every machine; it prints their SHA-256. |
| `Gen.java` | More computed signals; only its `music` kind (chords, bass and drums) is used here, for `aac/hev2-music.aac`. |
| `synth.py`, `silence.py` | The signal of the Opus fixtures (chords, a bass line, noise-burst drums, clicks, a sweep, silence, a stereo pan), and a copy of it with a silent stretch for the DTX file. |
| `modeswitch.c` | Encodes with libopus while forcing its mode, bandwidth, frame size and channel count to change every few frames. |
| `ContainerLab.java` | Puts raw Opus packets into Ogg (or WebM) with chosen timing fields. A development tool, not part of the mod. |
| `make-fixtures.sh` | Every recipe, with a comment on what each file is for. |

## What the script gives back

Checked on 01.11.2026 on macOS 26.4.1 (Apple silicon) with ffmpeg 9.0.1, LAME 4.0 and the libopus 1.6.1 release:

| Family | Result |
| --- | --- |
| `tools/fixtures/opus/` (12 files) | 11 byte for byte, `mode-switch.opus` included. `surround51.webm` decodes to the same samples; the WebM container bytes differ. |
| `tools/fixtures/aac/` (15 files) | 10 byte for byte (every ffmpeg file, and `hev2-implicit.aac` and `hev2-music.aac` from afconvert). `lc-afconvert.m4a`, `hev2-afconvert.m4a` and `he-mono-afconvert.m4a` decode to the same samples; afconvert's M4A container differs. `he-afconvert.m4a` and `he-implicit.aac` (afconvert's HE-AAC from the 48 kHz stereo signal) come out one byte shorter on this macOS, with the same length once decoded and a difference 110 dB or more below the signal. |
| `tools/fixtures/alac/` (4 files) | The two ffmpeg files byte for byte; the two afconvert files decode to the same samples. ALAC is lossless, so those are exactly FixtureSignal's samples. |
| `tools/fixtures/extras/` (22 files) | 13 byte for byte. The seven Matroska and WebM files and `ogg-flac.oga` decode to the same samples; their containers differ. `ogg-flac-stereo.oga` has no recipe here (see below). |

ffmpeg's Matroska muxer and afconvert write a date or a random id into the container, so those files cannot match byte
for byte; the script then compares the decoded samples (ffmpeg's MD5 of the decode) and says `audio`.

## Families without a generator here

Their generators were not kept, so these files cannot be remade from this folder. This is how they were made:

- `tools/vorbis-decoder-support/*.ogg`: computed test signals (clicks, noise bursts, sweeps), encoded with libvorbis
  1.3.7 by a small encoder program (its vendor field reads `scratch venc`, which `native-vorbis-decoder` checks) and,
  for `ff-stereo-48k.ogg`, with ffmpeg's own Vorbis encoder. Two were then rewritten on purpose:
  `lv-start-trim-1000.ogg` has its granule positions moved back 1000 samples, and `lv-blocks-64-128.ogg` has an
  identification header that declares blocks of 64 and 128 samples. `lv-cut-long-first.ogg` is a four-second chord with
  noise bursts, encoded with libvorbis and then cut 1.37 s in with `ffmpeg -ss 1.37 -c copy`. The `.libvorbis.f32` files
  hold every 61st frame of libvorbisfile 1.3.7's decode of the `.ogg` of the same name.
- `tools/fixtures/mp3/`: a computed signal (sweeps, clicks for short blocks, noise) encoded by ffmpeg 9 with libmp3lame
  for Layer III and with its own mp2 encoder for `layer2.mp2`. The tag of `cbr-lame.mp3` was then relabelled
  `LAME3.100`, with its CRC recomputed.
- `tools/fixtures/extras/ogg-flac-stereo.oga`: a stereo 16-bit test signal encoded by ffmpeg 9 straight into Ogg FLAC
  (`-c:a flac -f ogg`), 1.3 s at 44.1 kHz.

None of these files is the SampleFocus sound. That sound, `sounds/entity/smiley_damage.ogg`, ships in the jar, and
its copy decoded by libvorbis, `tools/vorbis-decoder-support/smiley_damage.libvorbis.f32`, is the reference the
`native-vorbis-decoder` check compares the mod's decoder against. Neither is made by these steps, and neither is in
the public source: there that check skips the parts that read them. See NOTICE.md.
