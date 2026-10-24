[CmdletBinding()]
param([switch]$Packaged)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\speed-variant-queue-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$javaDir = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin'
# The vote's speed-audio rules, run against the real MusicBattleTracks with a worker that records instead of starting
# ffmpeg. Nothing here starts ffmpeg or the game, or touches a game directory of the player's.
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','org.jcraft','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$jars = @($jars | Where-Object { [IO.Path]::GetFileName($_) -ne 'fastutil-8.3.1.jar' })
$classes = if ($Packaged) { "$projectDir/build/libs/blocky_music-0.1.0.jar" } else { "$projectDir/build/classes/java/main" }
$cp = (@($outputDir, $classes, "$projectDir/build/moddev/artifacts/neoforge-21.1.230-merged.jar", "$projectDir/build/moddev/artifacts/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar") + @($jars)) -join [IO.Path]::PathSeparator
$sources = @(
    'tools/SpeedVariantQueueChecks.java'
) | ForEach-Object { Join-Path $projectDir $_ }
& (Join-Path $javaDir 'javac.exe') -proc:none -encoding UTF-8 -cp $cp -d $outputDir @sources
if ($LASTEXITCODE -ne 0) { throw 'Speed variant queue checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $cp SpeedVariantQueueChecks $projectDir
if ($LASTEXITCODE -ne 0) { throw 'Speed variant queue regression failed.' }
