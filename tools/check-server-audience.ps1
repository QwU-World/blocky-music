$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectRoot 'build/server-audience-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm','dev.kosmx.player-anim')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$cp = (@($outputDir,"$projectRoot/build/classes/java/main","$projectRoot/build/moddev/artifacts/neoforge-21.1.230-merged.jar") + @($jars)) -join [IO.Path]::PathSeparator
$jdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
$sources = @("$projectRoot/tools/ServerAudienceChecks.java")
foreach ($name in @('ExplicitCameraSelection','BattleAudienceFocus')) {
    $source = "$projectRoot/src/main/java/ru/gora/musicbattle/data/$name.java"
    if (Test-Path -LiteralPath $source) { $sources += $source }
}
# Compile the real changed client handler, not a test substitute or stale class.
$sources += "$projectRoot/src/main/java/ru/gora/musicbattle/client/ClientBattleController.java"
$sources += "$projectRoot/src/main/java/ru/gora/musicbattle/client/DjCameraGalleryScreen.java"
$sources += "$projectRoot/tools/audience-check-support/net/neoforged/neoforge/network/PacketDistributor.java"
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $outputDir @sources
if ($LASTEXITCODE) { throw 'Audience test compilation failed' }
& "$jdk/java.exe" -cp $cp ServerAudienceChecks
if ($LASTEXITCODE) { throw 'Audience regression failed' }
