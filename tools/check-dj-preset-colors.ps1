[CmdletBinding()]
param([string[]]$ReadOnlyPresetFiles = @())

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\dj-preset-color-checks'
$mappedJar = Join-Path $projectDir 'build\moddev\artifacts\neoforge-21.1.230-merged.jar'
$compiledDir = Join-Path $projectDir 'build\classes\java\main'
if (!(Test-Path -LiteralPath $mappedJar) -or !(Test-Path -LiteralPath $compiledDir)) {
    throw 'Compile the project once to obtain mapped Minecraft classes and unchanged UI dependencies.'
}
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$cacheDir = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
$groups = @('com.google.code.gson', 'com.google.guava', 'com.mojang', 'io.netty', 'it.unimi.dsi',
    'org.apache.commons', 'org.slf4j', 'org.joml', 'org.lwjgl', 'net.neoforged.fancymodloader',
    'net.neoforged', 'cpw.mods', 'org.apache.logging.log4j', 'org.jetbrains', 'org.ow2.asm')
$dependencyJars = foreach ($group in $groups) {
    $groupDir = Join-Path $cacheDir $group
    if (Test-Path -LiteralPath $groupDir) {
        Get-ChildItem -LiteralPath $groupDir -Recurse -File -Filter '*.jar' |
            Where-Object { $_.Name -notmatch '(-sources|-javadoc|-natives-)' } |
            Select-Object -ExpandProperty FullName
    }
}
$classPath = (@($outputDir, $compiledDir, $mappedJar) + @($dependencyJars)) -join [IO.Path]::PathSeparator
$javaDir = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
$sources = @('src\main\java\ru\gora\musicbattle\client\ClientDjPresetStorage.java',
    'src\main\java\ru\gora\musicbattle\client\TrackEffectEditScreen.java',
    'src\main\java\ru\gora\musicbattle\client\ExactColorButton.java',
    'src\main\java\ru\gora\musicbattle\data\DjVisualEffectConfig.java',
    'src\main\java\ru\gora\musicbattle\data\DjVisualEffects.java',
    'src\main\java\ru\gora\musicbattle\data\TrackEffect.java',
    'src\main\java\ru\gora\musicbattle\data\TrackEffectSettings.java',
    'tools\preset-check-support\ru\gora\musicbattle\MusicBattleMod.java',
    'tools\DjPresetColorChecks.java') | ForEach-Object { Join-Path $projectDir $_ }
& (Join-Path $javaDir 'javac.exe') -proc:none -encoding UTF-8 -cp $classPath -d $outputDir @sources
if ($LASTEXITCODE -ne 0) { throw 'DJ preset/color checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $classPath ru.gora.musicbattle.client.DjPresetColorChecks (Join-Path $outputDir 'isolated-games') @ReadOnlyPresetFiles
if ($LASTEXITCODE -ne 0) { throw 'DJ preset/color regression failed.' }
