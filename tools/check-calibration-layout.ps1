$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectRoot 'build/calibration-layout-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$groups = @('com.ibm.icu','com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $directory = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $directory) { rg --files $directory -g '*.jar' | Where-Object { [IO.Path]::GetFileName($_) -notmatch '(-sources|-javadoc|-natives-|fastutil-8.3.1)' } }
}
$cp = (@($outputDir,"$projectRoot/build/classes/java/main","$projectRoot/build/moddev/artifacts/neoforge-21.1.230-merged.jar") + @($jars)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
# The screen is compiled from source next to the check, so the fitting rules measured are the ones in the tree; only its
# pure nested Fit class is loaded, never the screen itself. Text is measured by Minecraft's own FreeType glyph provider
# (GameTextMeasurer), and the judgement word's ink by the same provider (GlyphInk), so the LWJGL FreeType natives come from
# the cache with the rest of org.lwjgl.
$sources = @("$projectRoot/tools/CalibrationLayoutChecks.java", "$projectRoot/tools/GameTextMeasurer.java",
    "$projectRoot/tools/GlyphInk.java", "$projectRoot/src/main/java/ru/gora/musicbattle/client/MusicBattleCalibrationScreen.java")
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $outputDir @sources
if ($LASTEXITCODE) { throw 'Calibration layout test compilation failed' }
& "$jdk/java.exe" -cp $cp ru.gora.musicbattle.client.CalibrationLayoutChecks $projectRoot
if ($LASTEXITCODE) { throw 'Calibration layout regression failed' }
