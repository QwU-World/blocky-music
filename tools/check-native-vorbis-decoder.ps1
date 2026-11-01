[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\native-vorbis-decoder-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$javaDir = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
# The decoder is plain Java and is compiled here from its sources, so the check needs neither the game nor a Gradle
# build. LWJGL and its stb natives are only the reference: STBVorbis decodes the same files in this process. Without
# its natives the check compares with the frame counts and checksums recorded from STBVorbis, and says so.
# Minecraft's own decoder, JOrbisAudioStream (in the merged jar, with JOrbis and fastutil), is the reference for the
# timeline the game plays: the check reads it, it never starts the game.
$dependencyRoot = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$lwjgl = @(rg --files (Join-Path $dependencyRoot 'org.lwjgl') -g '*.jar' |
    Where-Object { [IO.Path]::GetFileName($_) -match '^lwjgl(-stb)?-3\.3\.3(-natives-windows)?\.jar$' })
$groups = @('org.jcraft','it.unimi.dsi')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $dependencyRoot $group
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$jars = @($jars | Where-Object { [IO.Path]::GetFileName($_) -ne 'fastutil-8.3.1.jar' })
$cp = (@($outputDir, "$projectDir/build/moddev/artifacts/neoforge-21.1.230-merged.jar") + $lwjgl + $jars) -join [IO.Path]::PathSeparator
$sources = @(
    'tools/NativeVorbisDecoderChecks.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/AudioFormatException.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/PcmStream.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/PcmSource.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/OggPageReader.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/VorbisDecoder.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/VorbisDecoderBits.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/VorbisDecoderMdct.java',
    'src/main/java/ru/gora/musicbattle/audio/codec/VorbisDecoderSetup.java'
) | ForEach-Object { Join-Path $projectDir $_ }
& (Join-Path $javaDir 'javac.exe') -proc:none -encoding UTF-8 -cp $cp -d $outputDir @sources
if ($LASTEXITCODE -ne 0) { throw 'Native Vorbis decoder checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $cp NativeVorbisDecoderChecks $projectDir
if ($LASTEXITCODE -ne 0) { throw 'Native Vorbis decoder regression failed.' }
