[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\native-vorbis-encoder-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$javaDir = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
# The pure-Java Vorbis encoder, part by part and stream by stream, read back by Minecraft's own decoder
# (JOrbisAudioStream, hence org.jcraft, fastutil and the merged jar), by LWJGL's stb_vorbis whole-file and pushdata
# (hence org.lwjgl and its natives) and by the mod's VorbisDecoder when the build has it. No ffmpeg, no game window.
$groups = @('org.lwjgl','org.jcraft','it.unimi.dsi')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$jars = @($jars | Where-Object { [IO.Path]::GetFileName($_) -ne 'fastutil-8.3.1.jar' })
$jars += @(rg --files (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.lwjgl') -g '*-3.3.3-natives-windows.jar')
$classes = "$projectDir/build/classes/java/main"
$cp = (@($outputDir, $classes, "$projectDir/build/moddev/artifacts/neoforge-21.1.230-merged.jar") + @($jars)) -join [IO.Path]::PathSeparator
$sources = @(
    'src/main/java/ru/gora/musicbattle/audio/codec/PcmStream.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/PcmSource.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/AudioFormatException.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/VorbisEncoder.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/VorbisEncoderBits.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/VorbisEncoderCore.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/VorbisEncoderFloor.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/VorbisEncoderLayout.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/VorbisEncoderResidue.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/VorbisEncoderSink.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/OggPageWriter.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/MdctForward.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/PsyModel.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/HuffmanLengths.java',
    'tools/NativeVorbisEncoderChecks.java'
) | ForEach-Object { Join-Path $projectDir $_ }
& (Join-Path $javaDir 'javac.exe') -proc:none -encoding UTF-8 -cp $cp -d $outputDir @sources
if ($LASTEXITCODE -ne 0) { throw 'Native Vorbis encoder checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $cp ru.gora.musicbattle.audio.codec.NativeVorbisEncoderChecks $projectDir
if ($LASTEXITCODE -ne 0) { throw 'Native Vorbis encoder regression failed.' }
