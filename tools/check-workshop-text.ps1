$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/workshop-text-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# The pure Workshop layouts compiled from source next to the check, so the rules measured are the ones in the tree; text
# is measured by Minecraft's own FreeType glyph provider at each GUI scale's oversample tier (GameTextMeasurer, whose
# FreeType natives come with org.lwjgl), and a dialog's lines are placed by their ink (GlyphInk). No Minecraft window.
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
# The compiled mod comes after the layouts compiled here, for the card renderer's badge words and colours.
$cp = (@($out, "$root/build/classes/java/main", "$root/build/moddev/artifacts/neoforge-21.1.230-merged.jar") + @($jars)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
$sources = @()
foreach ($name in @('TextStyle','TextMeasurer','Palette','Spectrum','CardGrid')) { $sources += "$root/src/main/java/ru/gora/musicbattle/client/ui/$name.java" }
foreach ($name in @('Rect','Tr','TextFit','Layout','Metrics','WorkshopStrings','Filters','ShellLayout','ListingLayout','CardLayout','TrackCardLayout','SetPageLayout','CommentsLayout','Form','FormLayout','AccountForm','HtmlText','UploadLayout','DialogLayout')) { $sources += "$root/src/main/java/ru/gora/musicbattle/client/workshop/layout/$name.java" }
$sources += "$root/tools/GameTextMeasurer.java"
$sources += "$root/tools/GlyphInk.java"
$sources += "$root/tools/WorkshopTextChecks.java"
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out @sources
if ($LASTEXITCODE) { throw 'Workshop text test compilation failed' }
& "$jdk/java.exe" -Djava.awt.headless=true -cp $cp ru.gora.musicbattle.client.WorkshopTextChecks $root
if ($LASTEXITCODE) { throw 'Workshop text regression failed' }
