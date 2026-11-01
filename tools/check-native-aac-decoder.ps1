$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/native-aac-decoder-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# The AAC (LC, HE-AAC, HE-AAC v2), ALAC, MP4/M4A and ADTS decoders that let players use M4A, MP4 and .aac songs without
# ffmpeg, compiled from source with nothing else on the classpath: no Minecraft, no LWJGL. The fixtures are in
# tools/fixtures/aac and tools/fixtures/alac, all encoded from a synthetic signal; the check builds every other variant
# itself (edit lists, gapless tags, fragmented layouts, damage). Nothing here runs ffmpeg or the game.
$cp = (@($out)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
$sources = @()
foreach ($name in @('AudioFormatException','PcmSource','PcmStream','UnsupportedFormatException','AacBits','AacConfig','AacDecoder','AacFilterBank','AacHuffman','AacHuffmanData','AacPs','AacPsData','AacSbr','AacSbrData','AacTables','AdtsDecoder','AlacDecoder','ChannelOrder','Fft','Mp4Decoder','Mp4Demuxer','OpusCeltAllocation','OpusCeltBands','OpusCeltDecoder','OpusCeltMdct','OpusCeltTables','OpusDecoder','OpusHead','OpusMultistreamDecoder','OpusPacketDecoder','OpusPcmStream','OpusRangeDecoder','OpusSilkChannel','OpusSilkDecoder','OpusSilkResampler','OpusSilkTables')) { $sources += "$root/src/main/java/ru/gora/musicbattle/audio/codec/$name.java" }
$sources += "$root/tools/NativeAacDecoderChecks.java"
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out @sources
if ($LASTEXITCODE) { throw 'Native AAC decoder checks failed to compile' }
& "$jdk/java.exe" -cp $cp NativeAacDecoderChecks $root
if ($LASTEXITCODE) { throw 'Native AAC decoder regression failed' }
