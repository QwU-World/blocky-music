[CmdletBinding()]
param([switch]$Packaged)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\battle-reliability-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$javaDir = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
# The note-numbering round trip runs the real server order, the real chart writer and the real client reader, so
# this check needs the compiled mod and Minecraft on the classpath - the pure sources below are still compiled
# here, because they are what the clock and camera checks are about.
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$jars = @($jars | Where-Object { [IO.Path]::GetFileName($_) -ne 'fastutil-8.3.1.jar' })
$modVersion = (Select-String -LiteralPath "$projectDir/gradle.properties" -Pattern '^mod_version=(.+)$').Matches[0].Groups[1].Value.Trim()
$classes = if ($Packaged) { "$projectDir/build/libs/blocky_music-$modVersion.jar" } else { "$projectDir/build/classes/java/main" }
$cp = (@($outputDir, $classes, "$projectDir/build/moddev/artifacts/neoforge-21.1.230-merged.jar", "$projectDir/build/moddev/artifacts/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar") + @($jars)) -join [IO.Path]::PathSeparator
$sources = @(
    'src/main/java/ru/gora/musicbattle/client/BattleCameraInterpolation.java',
    'src/main/java/ru/gora/musicbattle/data/BattleClock.java',
    'src/main/java/ru/gora/musicbattle/data/BattlePlacementMath.java',
    'src/main/java/ru/gora/musicbattle/data/PlacementAnimationClock.java',
    'src/main/java/ru/gora/musicbattle/server/DjPreloadAudience.java',
    'tools/BattleReliabilityChecks.java'
) | ForEach-Object { Join-Path $projectDir $_ }
& (Join-Path $javaDir 'javac.exe') -proc:none -encoding UTF-8 -cp $cp -d $outputDir @sources
if ($LASTEXITCODE -ne 0) { throw 'Battle reliability checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $cp BattleReliabilityChecks $projectDir $outputDir
if ($LASTEXITCODE -ne 0) { throw 'Battle reliability regression failed.' }
