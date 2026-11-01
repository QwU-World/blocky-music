[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\battle-input-latch-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
# Every lane press reaches the battle once and shows on the body once: the real press latch fed with key events
# between 50 ms ticks, and the real battle animation fed with judgements before, with and long after their keys.
# Same classpath as check-battle-animation-timing.ps1; no Minecraft window.
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
$sourcePaths = @('src/main/java/ru/gora/musicbattle/client/BattleLaneLatch.java',
    'src/main/java/ru/gora/musicbattle/client/ClientBattleAnimation.java',
    'tools/animation-check-support/ru/gora/musicbattle/MusicBattleMod.java', 'tools/BattleInputLatchChecks.java') |
    ForEach-Object { Join-Path $projectDir $_ }
& (Join-Path $javaDir 'javac.exe') -proc:none -encoding UTF-8 -cp $classPath -d $outputDir @sourcePaths
if ($LASTEXITCODE -ne 0) { throw 'Battle input latch checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $classPath ru.gora.musicbattle.client.BattleInputLatchChecks $projectDir
if ($LASTEXITCODE -ne 0) { throw 'Battle input latch regression failed.' }
