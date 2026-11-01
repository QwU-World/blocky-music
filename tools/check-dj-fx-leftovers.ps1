$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectRoot 'build/dj-fx-leftovers-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$groups = @('com.ibm.icu','com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.apache.maven','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $directory = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $directory) { rg --files $directory -g '*.jar' | Where-Object { [IO.Path]::GetFileName($_) -notmatch '(-sources|-javadoc|-natives-|fastutil-8.3.1)' } }
}
$cp = (@($outputDir,"$projectRoot/build/classes/java/main","$projectRoot/build/moddev/artifacts/neoforge-21.1.230-merged.jar","$projectRoot/build/moddev/artifacts/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar") + @($jars)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
# The DJ panel, the FX editor, the colour button, the visual FX list, the NPC settings and the camera gallery are compiled
# from source next to the check, so what is measured is the tree as it is; the screens are built and the buttons drawn
# for real in a stand-in game (Minecraft's bootstrap needs org.apache.maven for FML's mod list). Text is measured by
# Minecraft's own FreeType glyph provider (GameTextMeasurer), whose natives come with org.lwjgl. The language files are
# read as they are.
$sources = @("$projectRoot/tools/DjFxLeftoversChecks.java", "$projectRoot/tools/GameTextMeasurer.java",
    "$projectRoot/src/main/java/ru/gora/musicbattle/client/DjPanelScreen.java",
    "$projectRoot/src/main/java/ru/gora/musicbattle/client/TrackEffectEditScreen.java",
    "$projectRoot/src/main/java/ru/gora/musicbattle/client/ExactColorButton.java",
    "$projectRoot/src/main/java/ru/gora/musicbattle/client/DjVisualFxScreen.java",
    "$projectRoot/src/main/java/ru/gora/musicbattle/client/RapGodConfigScreen.java",
    "$projectRoot/src/main/java/ru/gora/musicbattle/client/DjCameraGalleryScreen.java",
    "$projectRoot/src/main/java/ru/gora/musicbattle/client/KitButton.java")
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $outputDir @sources
if ($LASTEXITCODE) { throw 'DJ and FX leftovers test compilation failed' }
& "$jdk/java.exe" -Djava.awt.headless=true -cp $cp ru.gora.musicbattle.client.DjFxLeftoversChecks $projectRoot
if ($LASTEXITCODE) { throw 'DJ and FX leftovers regression failed' }
