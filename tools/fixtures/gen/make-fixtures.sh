#!/bin/sh
# Remakes the decoder checks' audio fixtures from computed signals (no recorded audio) into OUT, then compares each
# file with the committed one in tools/fixtures. It never writes into tools/fixtures.
#
#   sh tools/fixtures/gen/make-fixtures.sh FAMILY [OUT]     FAMILY: aac-alac, extras, opus or all; OUT: build/fixture-gen
#
# Needs: a JDK 21 (BLOCKY_JDK or JAVA_HOME, else java on PATH), python3, ffmpeg 9 with libmp3lame and libopus, LAME,
# and for aac-alac macOS's afconvert. mode-switch.opus also needs a C compiler and LIBOPUS_SRC, the unpacked libopus
# 1.6.1 release (opus-1.6.1.tar.gz from https://downloads.xiph.org/releases/opus/); without it, it is skipped. Run from anywhere; paths are taken from this script's place in the repository.
set -e
FAMILY=${1:?usage: make-fixtures.sh aac-alac|extras|opus|all [OUT]}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
OUT=${2:-$ROOT/build/fixture-gen}
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
JDK=${BLOCKY_JDK:-${JAVA_HOME:-}}
JAVA=${JDK:+$JDK/bin/}java
JAVAC=${JDK:+$JDK/bin/}javac
SRC=$OUT/src
mkdir -p "$SRC"

# FixtureSignal OUT.wav rate channels seconds bits [seed] [level]: sweeps, tones, clicks, noise, silence and drum-like
# hits, cycling every 1.6 s; prints the SHA-256 of the samples and their count.
sig() { "$JAVA" "$HERE/FixtureSignal.java" "$@"; }

signals() {
    [ -f "$SRC/.done" ] && return 0
    cd "$SRC"
    sig s44.wav 44100 2 1.6 16 > s44.sha
    sig s48.wav 48000 2 1.6 16 > s48.sha
    sig m48.wav 48000 1 1.6 16 > m48.sha
    sig m22.wav 22050 1 1.2 16 > m22.sha
    sig c6.wav 48000 6 0.5 16 > c6.sha
    # Lossless fixtures: quieter and shorter, since noise does not compress.
    sig a16.wav 44100 2 0.45 16 3 0.5 > a16.sha
    sig a24.wav 48000 1 0.3 24 4 0.5 > a24.sha
    sig a16m.wav 44100 1 0.4 16 5 0.4 > a16m.sha
    sig a16v.wav 44100 2 0.06 16 6 0.9 > a16v.sha
    touch .done
}

aac_alac() {
    signals
    mkdir -p "$OUT/aac" "$OUT/alac"
    cd "$OUT/aac"
    # Apple: iTunSMPB gapless tag, no edit list; the moov comes first.
    afconvert -f m4af -d aac -b 96000 "$SRC/s44.wav" lc-afconvert.m4a
    afconvert -f m4af -d aach -b 48000 "$SRC/s48.wav" he-afconvert.m4a
    afconvert -f m4af -d aacp -b 24000 "$SRC/s44.wav" hev2-afconvert.m4a
    afconvert -f m4af -d aach -b 24000 "$SRC/m48.wav" he-mono-afconvert.m4a
    # Apple ADTS: SBR and PS can only be signalled implicitly in ADTS.
    afconvert -f adts -d aach -b 48000 "$SRC/s48.wav" he-implicit.aac
    afconvert -f adts -d aacp -b 24000 "$SRC/s44.wav" hev2-implicit.aac
    # HE-AAC v2 from Gen's music-like signal (computed chords, bass and drums): Apple's encoder sends its PS header only
    # in the frame before each SBR header, so a decode that starts part way in must wait for both.
    "$JAVA" "$HERE/Gen.java" music 44100 2 12 "$SRC/music44s.wav"
    ffmpeg -v error -y -i "$SRC/music44s.wav" -t 5 "$SRC/m44s5.wav"
    afconvert -f adts -d aacp -b 24000 "$SRC/m44s5.wav" hev2-music.aac
    # ffmpeg: edit list (media time 1024), moov after mdat, intensity stereo, PNS, TNS, short windows.
    ffmpeg -v error -y -i "$SRC/s44.wav" -c:a aac -b:a 64k -aac_is 1 -aac_pns 1 -aac_tns 1 -aac_ms 1 lc-ffmpeg-tools.m4a
    # Fragmented: empty moov, every packet in moof/mdat pairs with default-base-is-moof.
    ffmpeg -v error -y -i "$SRC/s48.wav" -c:a aac -b:a 64k -movflags +frag_keyframe+empty_moov+default_base_moof \
        -frag_duration 400000 lc-fragmented.m4a
    # +frag_keyframe without empty_moov: the first samples sit in the moov, the rest in fragments.
    ffmpeg -v error -y -i "$SRC/s44.wav" -c:a aac -b:a 64k -movflags +frag_keyframe -frag_duration 500000 lc-hybrid.m4a
    # DASH single-file layout with a global sidx, as YouTube's audio-only .m4a streams are stored.
    ffmpeg -v error -y -i "$SRC/s44.wav" -c:a aac -b:a 64k -movflags +dash+global_sidx -frag_duration 400000 lc-dash.m4a
    ffmpeg -v error -y -i "$SRC/c6.wav" -c:a aac -b:a 160k lc-5.1.m4a
    ffmpeg -v error -y -i "$SRC/m22.wav" -c:a aac -b:a 32k -f adts lc-mono-22k.aac
    ffmpeg -v error -y -i "$SRC/s44.wav" -c:a aac -b:a 64k -f adts lc-stereo-44k.aac
    # MPEG-TS as an HLS download saved without ffmpeg: 0.3 s of digital silence, then the first 0.6 s of the signal.
    ffmpeg -v error -y -f lavfi -i anullsrc=r=44100:cl=stereo:d=0.3 -i "$SRC/s44.wav" \
        -filter_complex "[0][1]concat=n=2:v=0:a=1,atrim=0:0.9" -c:a aac -b:a 64k -threads 1 -fflags +bitexact \
        -flags:a +bitexact -map_metadata -1 -f mpegts lc-silent-intro.ts
    cd "$OUT/alac"
    afconvert -f m4af -d alac "$SRC/a16.wav" alac-afconvert-16.m4a
    afconvert -f m4af -d alac "$SRC/a24.wav" alac-afconvert-24-mono.m4a
    ffmpeg -v error -y -i "$SRC/a16m.wav" -c:a alac alac-ffmpeg-mono.m4a
    # compression_level 0 makes ffmpeg write every frame verbatim (the escape path).
    ffmpeg -v error -y -i "$SRC/a16v.wav" -c:a alac -compression_level 0 alac-ffmpeg-verbatim.m4a
}

extras() {
    signals
    O=$OUT/extras
    mkdir -p "$O/tmp/hls"
    cd "$O"
    F() { ffmpeg -v error -y -threads 1 -bitexact "$@"; }
    # MPEG-TS: HLS segments joined (named .m4a, as yt-dlp leaves them without ffmpeg), MP3, Opus, 5.1 Opus, .m2ts.
    F -i "$SRC/s44.wav" -c:a aac -b:a 48k -f hls -hls_time 0.5 -hls_segment_type mpegts \
        -hls_segment_filename "$O/tmp/hls/seg%02d.ts" "$O/tmp/hls/list.m3u8"
    cat "$O"/tmp/hls/seg*.ts > ts-hls-aac.m4a
    F -i "$SRC/s44.wav" -c:a libmp3lame -b:a 48k -f mpegts ts-mp3.ts
    F -i "$SRC/s48.wav" -c:a libopus -b:a 48k -f mpegts ts-opus.ts
    F -i "$SRC/c6.wav" -c:a libopus -b:a 96k -f mpegts ts-opus51.ts
    # The same Opus packets as ts-opus.ts, cut into pieces by the segment muxer (each restarts its counters and carries
    # the start trim again) and joined.
    F -i "$SRC/s48.wav" -c:a libopus -b:a 48k tmp/o.opus
    F -i tmp/o.opus -c copy -f segment -segment_time 0.2 tmp/piece%03d.ts
    cat $(ls tmp/piece[0-9]*.ts | sort) > ts-opus-joined.ts
    F -i "$SRC/m48.wav" -t 0.8 -c:a aac -b:a 32k -f mpegts -mpegts_m2ts_mode 1 ts-aac.m2ts
    # Opus in MP4: plain (edit list), fragmented (no edit duration), 5.1.
    F -i "$SRC/s48.wav" -c:a libopus -b:a 48k mp4-opus.mp4
    F -i "$SRC/m48.wav" -t 0.8 -c:a libopus -b:a 32k -movflags +frag_keyframe+empty_moov+default_base_moof \
        -frag_duration 300000 mp4-opus-frag.mp4
    F -i "$SRC/c6.wav" -c:a libopus -b:a 96k mp4-opus51.mp4
    # Matroska: Vorbis (ffmpeg's own encoder) copied from Ogg into WebM, AAC, MP3, FLAC and PCM in .mka, 5.1 AAC.
    F -i "$SRC/s44.wav" -c:a vorbis -strict -2 -b:a 64k tmp/v.ogg
    F -i tmp/v.ogg -c:a copy mkv-vorbis.webm
    F -i "$SRC/s44.wav" -c:a aac -b:a 48k mkv-aac.mka
    F -i "$SRC/c6.wav" -c:a aac -b:a 96k mkv-aac51.mka
    F -i "$SRC/m48.wav" -t 0.8 -c:a libmp3lame -b:a 32k mkv-mp3.mka
    F -i "$SRC/m22.wav" -t 0.3 -c:a flac mkv-flac.mka
    F -i "$SRC/m22.wav" -t 0.2 -c:a pcm_s16le mkv-pcm.mka
    # Vorbis written by ffmpeg's encoder straight into WebM (CodecDelay 1024).
    F -i "$SRC/s44.wav" -t 0.5 -c:a vorbis -strict -2 -b:a 64k mkv-vorbis-direct.webm
    # Ogg FLAC: the FLAC packets of mkv-flac.mka copied into Ogg, so the same samples. (ogg-flac-stereo.oga has no
    # recipe here; see README.md.)
    F -i mkv-flac.mka -c:a copy -f ogg ogg-flac.oga
    # WAV ADPCM.
    F -i "$SRC/m48.wav" -t 0.8 -c:a adpcm_ima_wav wav-ima.wav
    F -i "$SRC/s44.wav" -t 0.25 -c:a adpcm_ms wav-ms.wav
    # Free-format MP3 (LAME writes an Info tag; -t leaves it out).
    lame --quiet --freeformat -b 64 "$SRC/m22.wav" mp3-free.mp3
    lame --quiet -t --freeformat -b 80 "$SRC/m48.wav" mp3-free-notag.mp3
    rm -r "$O/tmp"
}

opus() {
    G=$OUT/opus-src
    O=$OUT/opus
    mkdir -p "$G" "$O"
    # synth.py OUT.wav seconds rate channels seed: chords, a bass line, noise-burst drums, clicks, a sweep, silence and a
    # stereo pan.
    python3 -I "$HERE/synth.py" "$G/st3.wav" 3 48000 2 21
    python3 -I "$HERE/synth.py" "$G/mo3.wav" 3 48000 1 22
    python3 -I "$HERE/synth.py" "$G/s51.wav" 2 48000 6 23
    python3 -I "$HERE/silence.py" "$G/mo3.wav" "$G/mo3-gap.wav" 1.0 2.2
    E() { ffmpeg -hide_banner -loglevel error -y "$@"; }
    E -i "$G/mo3.wav" -c:a libopus -b:a 8k -application voip -cutoff 4000 -fflags +bitexact "$O/silk-nb-mono.opus"
    E -i "$G/mo3.wav" -c:a libopus -b:a 12k -application voip -cutoff 6000 -frame_duration 60 -fflags +bitexact \
        "$O/silk-mb-mono-60ms.opus"
    E -i "$G/st3.wav" -c:a libopus -b:a 16k -application voip -cutoff 8000 -frame_duration 10 -fflags +bitexact \
        "$O/silk-wb-stereo-10ms.opus"
    E -i "$G/st3.wav" -c:a libopus -b:a 32k -application voip -fec 1 -packet_loss 20 -fflags +bitexact \
        "$O/hybrid-fec-stereo.opus"
    E -i "$G/mo3-gap.wav" -c:a libopus -b:a 12k -application voip -dtx 1 -fflags +bitexact "$O/dtx-mono.opus"
    E -i "$G/st3.wav" -c:a libopus -b:a 96k -fflags +bitexact "$O/celt-stereo-96k.opus"
    E -i "$G/mo3.wav" -c:a libopus -b:a 48k -frame_duration 2.5 -fflags +bitexact "$O/celt-mono-2.5ms.opus"
    E -i "$G/st3.wav" -c:a libopus -b:a 48k -frame_duration 120 -fflags +bitexact "$O/celt-stereo-120ms.opus"
    E -i "$G/s51.wav" -c:a libopus -b:a 96k -mapping_family 1 -fflags +bitexact "$O/surround51.opus"
    E -i "$G/st3.wav" -c:a libopus -b:a 64k -fflags +bitexact "$O/stereo.webm"
    # surround51.webm: the first second of surround51.opus's packets, copied into WebM.
    E -i "$O/surround51.opus" -t 1 -c copy "$O/surround51.webm"
    # mode-switch.opus: libopus's own encoder, its mode, bandwidth, frame size and channel count forced to change every
    # few frames (modeswitch.c), the packets put into Ogg by ContainerLab. The library is compiled here from the release
    # sources with plain compiler calls (its build system is not run): the float build with FLOAT_APPROX, as configure
    # enables it on ARM and x86-64, no SIMD and no run-time CPU detection, and -ffp-contract=off, so that a*b+c is never
    # fused, as in Java. The same scalar build is the reference the Opus check's SHA-256 values come from.
    if [ -n "${LIBOPUS_SRC:-}" ]; then
        L=$G/libopus
        mkdir -p "$L/obj"
        printf '%s\n' '#define OPUS_BUILD 1' '#define FLOAT_APPROX 1' '#define VAR_ARRAYS 1' '#define HAVE_LRINT 1' \
            '#define HAVE_LRINTF 1' '#define PACKAGE_VERSION "1.6.1-scalar-reference"' > "$L/config.h"
        CFLAGS="-O2 -ffp-contract=off -fno-fast-math -DHAVE_CONFIG_H -I$L -I$LIBOPUS_SRC/include -I$LIBOPUS_SRC/celt"
        CFLAGS="$CFLAGS -I$LIBOPUS_SRC/silk -I$LIBOPUS_SRC/silk/float -I$LIBOPUS_SRC/src -w"
        for f in "$LIBOPUS_SRC"/celt/*.c "$LIBOPUS_SRC"/silk/*.c "$LIBOPUS_SRC"/silk/float/*.c; do
            case "$f" in *opus_custom_demo.c) continue ;; esac
            cc $CFLAGS -c "$f" -o "$L/obj/$(echo "$f" | sed "s#$LIBOPUS_SRC/##; s#/#_#g; s#\.c\$#.o#")"
        done
        for f in opus opus_decoder opus_encoder opus_multistream opus_multistream_encoder opus_multistream_decoder \
                repacketizer extensions analysis mlp mlp_data mapping_matrix opus_projection_encoder \
                opus_projection_decoder; do
            cc $CFLAGS -c "$LIBOPUS_SRC/src/$f.c" -o "$L/obj/src_$f.o"
        done
        rm -f "$L/libopus_scalar.a"
        ar rcs "$L/libopus_scalar.a" "$L"/obj/*.o
        cc $CFLAGS -o "$G/modeswitch" "$HERE/modeswitch.c" "$L/libopus_scalar.a" -lm
        "$G/modeswitch" "$G/st3.wav" "$G/modeswitch.pkts" 40000
        mkdir -p "$G/classes"
        "$JAVAC" -proc:none -encoding UTF-8 -cp "$ROOT/build/classes/java/main" -d "$G/classes" "$HERE/ContainerLab.java"
        "$JAVA" -cp "$G/classes:$ROOT/build/classes/java/main" ru.gora.musicbattle.audio.codec.ContainerLab ogg \
            "$G/modeswitch.pkts" "$O/mode-switch.opus" preskip=312 eosgranule=143000
    else
        echo "skip mode-switch.opus: set LIBOPUS_SRC to the unpacked libopus 1.6.1 release"
    fi
}

audio_md5() { ffmpeg -v error -i "$1" -map 0:a:0 -f md5 - 2>/dev/null; }

compare() {
    for made in "$OUT/$1"/*; do
        name=$(basename "$made")
        committed=$ROOT/tools/fixtures/$1/$name
        if [ ! -f "$committed" ]; then
            echo "  new      $1/$name"
        elif cmp -s "$made" "$committed"; then
            echo "  same     $1/$name"
        elif a=$(audio_md5 "$made") && [ -n "$a" ] && [ "$a" = "$(audio_md5 "$committed")" ]; then
            # Containers that stamp a date or a random id (afconvert's M4A, ffmpeg's Matroska) differ in those bytes.
            echo "  audio    $1/$name (decodes to the same samples; container bytes differ)"
        else
            echo "  differs  $1/$name"
        fi
    done
    for committed in "$ROOT/tools/fixtures/$1"/*; do
        [ -f "$OUT/$1/$(basename "$committed")" ] || echo "  not made $1/$(basename "$committed") (see README.md)"
    done
}

case $FAMILY in
    aac-alac) aac_alac; compare aac; compare alac ;;
    extras) extras; compare extras ;;
    opus) opus; compare opus ;;
    all) aac_alac; extras; opus; for f in aac alac extras opus; do compare $f; done ;;
    *) echo "unknown family $FAMILY" >&2; exit 2 ;;
esac
