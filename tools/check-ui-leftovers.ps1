$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/ui-leftovers-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# The second wave's interface leftovers: field hints, the exact colour picker, the playlist entry's effects, the speaker
# dialog's units and numbers, and the FX wording. The screens come from the compiled classes; text is measured in Nunito
# through java.awt, and the toolkit's tooltip card with Minecraft's own glyph provider (GameTextMeasurer, whose FreeType
# natives come with org.lwjgl); the language files are read as they are. No Minecraft window.
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$cp = (@($out, "$root/build/classes/java/main", "$root/build/moddev/artifacts/neoforge-21.1.230-merged.jar", "$root/build/moddev/artifacts/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar") + @($jars)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out "$root/tools/UiLeftoversChecks.java" "$root/tools/GameTextMeasurer.java"
if ($LASTEXITCODE) { throw 'UI leftovers test compilation failed' }
& "$jdk/java.exe" -Djava.awt.headless=true -cp $cp ru.gora.musicbattle.client.UiLeftoversChecks $root
if ($LASTEXITCODE) { throw 'UI leftovers regression failed' }
