$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/native-audio-decoder-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# The MP3, FLAC and WAV decoders that replace ffmpeg for reading songs, compiled from source with nothing else on the
# classpath: no Minecraft, no LWJGL, no Gson. The MP3 fixtures are in tools/fixtures/mp3; FLAC and WAV are built by the
# check itself. Nothing here runs ffmpeg or the game.
$cp = (@($out)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
$sources = @()
foreach ($name in @('AudioFormatException','PcmSource','PcmStream','UnsupportedFormatException','FlacDecoder','Mp3Decoder','SincResampler','WavAdpcm','WavDecoder')) { $sources += "$root/src/main/java/ru/gora/musicbattle/audio/codec/$name.java" }
$sources += "$root/tools/NativeAudioDecoderChecks.java"
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out @sources
if ($LASTEXITCODE) { throw 'Native audio decoder checks failed to compile' }
& "$jdk/java.exe" -cp $cp NativeAudioDecoderChecks $root
if ($LASTEXITCODE) { throw 'Native audio decoder regression failed' }
