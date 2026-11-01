$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/native-opus-decoder-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# The Opus decoder (a port of libopus 1.6.1's) and its Ogg and WebM readers, compiled from source with nothing else on
# the classpath: no Minecraft, no LWJGL, no Gson. The fixtures are in tools/fixtures/opus; every other file is built by
# the check itself. Nothing here runs ffmpeg or the game.
$cp = (@($out)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
$sources = @()
foreach ($name in @('AudioFormatException','PcmSource','PcmStream','UnsupportedFormatException','AacBits','AacConfig','AacDecoder','AacFilterBank','AacHuffman','AacHuffmanData','AacPs','AacPsData','AacSbr','AacSbrData','AacTables','AdtsDecoder','ChannelOrder','Fft','FlacDecoder','MatroskaAudio','Mp3Decoder','OggPageReader','OggPageWriter','OpusCeltAllocation','OpusCeltBands','OpusCeltDecoder','OpusCeltMdct','OpusCeltTables','OpusDecoder','OpusHead','OpusMultistreamDecoder','OpusPacketDecoder','OpusPcmStream','OpusRangeDecoder','OpusSilkChannel','OpusSilkDecoder','OpusSilkResampler','OpusSilkTables','SincResampler','VorbisDecoder','VorbisDecoderBits','VorbisDecoderMdct','VorbisDecoderSetup','WavAdpcm','WavDecoder','WebmDecoder')) { $sources += "$root/src/main/java/ru/gora/musicbattle/audio/codec/$name.java" }
$sources += "$root/tools/NativeOpusDecoderChecks.java"
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out @sources
if ($LASTEXITCODE) { throw 'Native Opus decoder checks failed to compile' }
& "$jdk/java.exe" -cp $cp NativeOpusDecoderChecks $root
if ($LASTEXITCODE) { throw 'Native Opus decoder regression failed' }
