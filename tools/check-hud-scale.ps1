$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectRoot 'build/hud-scale-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$groups = @('com.mojang','net.neoforged','org.joml','org.slf4j','com.google.code.gson','com.google.guava','it.unimi.dsi','org.apache.commons','org.apache.logging.log4j','io.netty','cpw.mods','net.neoforged.fancymodloader','org.jetbrains','org.ow2.asm','com.ibm.icu','org.lwjgl')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -File -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$cp = (@($outputDir, "$projectRoot/build/moddev/artifacts/neoforge-21.1.230-merged.jar") + @($jars)) -join [IO.Path]::PathSeparator
$jdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
$sources = @("$projectRoot/src/main/java/ru/gora/musicbattle/client/BattleHudScale.java", "$projectRoot/tools/HudScaleChecks.java")
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $outputDir @sources
if ($LASTEXITCODE) { throw 'HUD scale checks compilation failed' }
& "$jdk/java.exe" -cp $cp ru.gora.musicbattle.client.HudScaleChecks
if ($LASTEXITCODE) { throw 'HUD scale regression failed' }
