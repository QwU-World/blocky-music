$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectRoot 'build/battle-panel-geometry-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$groups = @('com.ibm.icu','com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $directory = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $directory) { rg --files $directory -g '*.jar' | Where-Object { [IO.Path]::GetFileName($_) -notmatch '(-sources|-javadoc|-natives-)' } }
}
$cp = (@($outputDir,"$projectRoot/build/classes/java/main","$projectRoot/build/moddev/artifacts/neoforge-21.1.230-merged.jar") + @($jars)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
# The production renderer and its pure helpers are compiled from source next to the check; the real RenderType needs
# game registries, so a stand-in supplies only its GUI token. Everything the header's drawing reaches for comes from
# the compiled mod, and only its pure layout runs here.
$sources = @("$projectRoot/tools/BattlePanelGeometryChecks.java", "$projectRoot/tools/GameTextMeasurer.java",
    "$projectRoot/src/main/java/ru/gora/musicbattle/client/BattlePanelRenderer.java",
    "$projectRoot/tools/note-render-support/net/minecraft/client/renderer/RenderType.java")
foreach ($path in @('client/BattleNoteShape','data/NoteAppearance','data/NoteSpeed')) {
    $source = "$projectRoot/src/main/java/ru/gora/musicbattle/$path.java"
    if (Test-Path -LiteralPath $source) { $sources += $source }
}
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $outputDir @sources
if ($LASTEXITCODE) { throw 'Battle panel geometry test compilation failed' }
& "$jdk/java.exe" -cp $cp ru.gora.musicbattle.client.BattlePanelGeometryChecks $projectRoot
if ($LASTEXITCODE) { throw 'Battle panel geometry regression failed' }
