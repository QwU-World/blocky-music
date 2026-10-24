[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectRoot 'build/runtime-regression-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dependencyDir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dependencyDir) { rg --files $dependencyDir -g '*.jar' | Where-Object { $_ -notmatch '(-sources|-javadoc|-natives-)' } }
}
$cp = (@($outputDir, "$projectRoot/build/classes/java/main", "$projectRoot/build/moddev/artifacts/neoforge-21.1.230-merged.jar") + @($jars)) -join [IO.Path]::PathSeparator
$sources = @(
    'tools/DjAudioEffectsChecks.java',
    'tools/CameraCollisionChecks.java',
    'tools/BattleAnimationInputChecks.java'
) | ForEach-Object { Join-Path $projectRoot $_ }
$jdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $outputDir @sources
if ($LASTEXITCODE) { throw 'Runtime regression test compilation failed' }
foreach ($main in @('ru.gora.musicbattle.client.DjAudioEffectsChecks','ru.gora.musicbattle.data.CameraCollisionChecks','ru.gora.musicbattle.data.BattleAnimationInputChecks')) {
    & "$jdk/java.exe" -cp $cp $main
    if ($LASTEXITCODE) { throw "$main failed" }
}
