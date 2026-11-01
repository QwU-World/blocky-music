[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\preview-animation-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
# The editor's gameplay preview poses the player as a battle poses its own side: the preview's held lanes (hold notes
# and keys down) and the battle's inputs side by side, frame by frame, through the real clips and the real Player
# Animator layer. TrackGameplayPreviewScreen comes from the compiled classes; same classpath as
# check-battle-animation-timing.ps1. No Minecraft window.
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi',
    'org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader',
    'net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm','dev.kosmx.player-anim')
$dependencyJars = foreach ($group in $groups) {
    $groupDir = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\$group"
    if (Test-Path -LiteralPath $groupDir) {
        Get-ChildItem -LiteralPath $groupDir -Recurse -File -Filter '*.jar' |
            Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName
    }
}
$classPath = (@($outputDir, "$projectDir\build\classes\java\main",
    "$projectDir\build\moddev\artifacts\neoforge-21.1.230-merged.jar") + @($dependencyJars)) -join [IO.Path]::PathSeparator
$javaDir = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
$sourcePaths = @('src/main/java/ru/gora/musicbattle/client/ClientBattleAnimation.java',
    'tools/animation-check-support/ru/gora/musicbattle/MusicBattleMod.java', 'tools/PreviewAnimationChecks.java') |
    ForEach-Object { Join-Path $projectDir $_ }
& (Join-Path $javaDir 'javac.exe') -proc:none -encoding UTF-8 -cp $classPath -d $outputDir @sourcePaths
if ($LASTEXITCODE -ne 0) { throw 'Preview animation checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $classPath ru.gora.musicbattle.client.PreviewAnimationChecks $projectDir
if ($LASTEXITCODE -ne 0) { throw 'Preview animation regression failed.' }
