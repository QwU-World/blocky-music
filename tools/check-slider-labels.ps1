$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectRoot 'build/slider-labels-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$groups = @('com.ibm.icu','com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $directory = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $directory) { rg --files $directory -g '*.jar' | Where-Object { [IO.Path]::GetFileName($_) -notmatch '(-sources|-javadoc|-natives-|fastutil-8.3.1)' } }
}
$cp = (@($outputDir,"$projectRoot/build/classes/java/main","$projectRoot/build/moddev/artifacts/neoforge-21.1.230-merged.jar") + @($jars)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
# The toolkit's Slider is compiled from source next to the check, so the geometry measured is the tree's; only its pure
# layout and label run here. Glyph widths and ink boxes come from Minecraft's own FreeType glyph provider (GameTextMeasurer,
# GlyphInk), so the LWJGL FreeType natives come from the cache with the rest of org.lwjgl.
$sources = @("$projectRoot/tools/SliderLabelChecks.java", "$projectRoot/tools/GameTextMeasurer.java", "$projectRoot/tools/GlyphInk.java",
    "$projectRoot/src/main/java/ru/gora/musicbattle/client/ui/Slider.java")
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $outputDir @sources
if ($LASTEXITCODE) { throw 'Slider label test compilation failed' }
& "$jdk/java.exe" -cp $cp ru.gora.musicbattle.client.SliderLabelChecks $projectRoot
if ($LASTEXITCODE) { throw 'Slider label regression failed' }
