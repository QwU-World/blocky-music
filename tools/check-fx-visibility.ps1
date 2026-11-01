$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/fx-visibility-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$groups = @('com.google.code.gson','com.google.guava','com.mojang','com.ibm.icu','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm','dev.kosmx.player-anim')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$cp = (@($out, "$root/build/classes/java/main", "$root/build/moddev/artifacts/neoforge-21.1.230-merged.jar", "$root/build/moddev/artifacts/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar") + @($jars)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
$sources = @("$root/tools/FxVisibilityChecks.java", "$root/tools/FxHudRenderChecks.java", "$root/tools/FxHudEditorChecks.java", "$root/src/main/java/ru/gora/musicbattle/client/TrackGameplayPreviewScreen.java", "$root/src/main/java/ru/gora/musicbattle/client/TrackEffectEditScreen.java")
foreach ($name in @('data/TrackEffectSettings','data/TrackEffect','client/ClientTrackEffects','client/ClientDjPresetStorage','client/SceneVisibilityCache','client/ClientBattleController','client/ClientGameEvents')) {
    $sources += "$root/src/main/java/ru/gora/musicbattle/$name.java"
}
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out @sources
if ($LASTEXITCODE) { throw 'FX visibility test compilation failed' }
& "$jdk/java.exe" -cp $cp FxVisibilityChecks
if ($LASTEXITCODE) { throw 'FX visibility regression failed' }
