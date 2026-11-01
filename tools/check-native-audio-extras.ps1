$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/native-audio-extras-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# The formats and fixes that completed the mod's own converter (MPEG-TS, Opus in MP4, Vorbis/AAC/MP3/FLAC/PCM in
# Matroska, RIFX and ADPCM WAV, free-format and rate-changing MP3, the speed copies' limiter, the server's granule
# test), compiled from source with nothing else on the classpath: no Minecraft, no LWJGL. The fixtures are in
# tools/fixtures/extras, made from synthetic signals; everything else is built by the check. Nothing runs ffmpeg.
$cp = (@($out)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
$sources = @()
foreach ($name in @('AacBits','AacConfig','AacDecoder','AacFilterBank','AacHuffman','AacHuffmanData','AacPs','AacPsData','AacSbr','AacSbrData','AacTables','AdtsDecoder','AlacDecoder','AudioDecoders','AudioFormatException','ChannelDownmix','ChannelOrder','Fft','FlacDecoder','MatroskaAudio','Mp3Decoder','Mp4Decoder','Mp4Demuxer','MpegTsDecoder','OggFlacDecoder','OggPageReader','OggPageWriter','OpusCeltAllocation','OpusCeltBands','OpusCeltDecoder','OpusCeltMdct','OpusCeltTables','OpusDecoder','OpusHead','OpusMultistreamDecoder','OpusPacketDecoder','OpusPcmStream','OpusRangeDecoder','OpusSilkChannel','OpusSilkDecoder','OpusSilkResampler','OpusSilkTables','PcmSource','PcmStream','PeakLimiter','SincResampler','UnsupportedFormatException','VorbisDecoder','VorbisDecoderBits','VorbisDecoderMdct','VorbisDecoderSetup','WavAdpcm','WavDecoder','WebmDecoder')) { $sources += "$root/src/main/java/ru/gora/musicbattle/audio/codec/$name.java" }
$sources += "$root/tools/NativeAudioExtrasChecks.java"
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out @sources
if ($LASTEXITCODE) { throw 'Native audio extras checks failed to compile' }
& "$jdk/java.exe" -cp $cp ru.gora.musicbattle.audio.codec.NativeAudioExtrasChecks $root
if ($LASTEXITCODE) { throw 'Native audio extras regression failed' }
