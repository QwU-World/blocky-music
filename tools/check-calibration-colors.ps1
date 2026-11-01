$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectRoot 'build/calibration-color-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$groups = @('com.ibm.icu','com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $directory = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $directory) {
        rg --files $directory -g '*.jar' | Where-Object { [IO.Path]::GetFileName($_) -notmatch '(-sources|-javadoc|-natives-|fastutil-8.3.1)' }
    }
}
$classpath = (@(
    $outputDir,
    "$projectRoot/build/classes/java/main",
    "$projectRoot/build/moddev/artifacts/neoforge-21.1.230-merged.jar",
    "$projectRoot/build/moddev/artifacts/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar"
) + @($jars)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
$sources = @(
    "$projectRoot/tools/CalibrationColorRenderChecks.java",
    "$projectRoot/src/main/java/ru/gora/musicbattle/client/MusicBattleCalibrationScreen.java"
)
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $classpath -d $outputDir @sources
if ($LASTEXITCODE) { throw 'Calibration color render test compilation failed' }
& "$jdk/java.exe" -cp $classpath CalibrationColorRenderChecks
if ($LASTEXITCODE) { throw 'Calibration color render regression failed' }
