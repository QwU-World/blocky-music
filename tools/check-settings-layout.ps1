$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/settings-layout-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# The general settings screen's layout and the battle backdrop as pure arithmetic, text measured with java.awt. Minecraft
# is on the classpath only so BattleHudScale compiles; the check calls its window-free overload.
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-|fastutil-8.3.1)' | Select-Object -ExpandProperty FullName }
}
$cp = (@($out, "$root/build/moddev/artifacts/neoforge-21.1.230-merged.jar") + @($jars)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
$sources = @("$root/tools/SettingsLayoutChecks.java")
foreach ($name in @('SettingsScreenLayout','BattleBackdrop','BattleHudScale')) { $sources += "$root/src/main/java/ru/gora/musicbattle/client/$name.java" }
foreach ($name in @('TextStyle','TextMeasurer','Palette')) { $sources += "$root/src/main/java/ru/gora/musicbattle/client/ui/$name.java" }
foreach ($name in @('Rect','TextFit','Metrics')) { $sources += "$root/src/main/java/ru/gora/musicbattle/client/workshop/layout/$name.java" }
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out @sources
if ($LASTEXITCODE) { throw 'Settings layout test compilation failed' }
& "$jdk/java.exe" -Djava.awt.headless=true -cp $cp ru.gora.musicbattle.client.SettingsLayoutChecks $root
if ($LASTEXITCODE) { throw 'Settings layout regression failed' }
