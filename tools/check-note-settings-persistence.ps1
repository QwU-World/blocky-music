$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/note-settings-persistence-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
# Minecraft uses 8.5.12; a tool-only 8.3.1 also exists in the local Gradle cache.
$jars = @($jars | Where-Object { [IO.Path]::GetFileName($_) -ne 'fastutil-8.3.1.jar' })
$jars += @(rg --files (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.lwjgl') -g '*-3.3.3-natives-windows.jar')
$cp = (@($out, "$root/build/classes/java/main", "$root/build/moddev/artifacts/neoforge-21.1.230-merged.jar", "$root/build/moddev/artifacts/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar") + @($jars)) -join [IO.Path]::PathSeparator
$sources = @("$root/tools/NoteSettingsPersistenceChecks.java")
foreach ($name in @('client/ClientBattleSettings','client/ClientNoteAppearanceConfig','data/NoteAppearance','data/NoteSpeed','network/SettingsPayload','network/NoteAppearanceCodec')) {
    $sources += "$root/src/main/java/ru/gora/musicbattle/$name.java"
}
$jdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out @sources
if ($LASTEXITCODE) { throw 'Settings persistence test compilation failed' }
& "$jdk/java.exe" -cp $cp NoteSettingsPersistenceChecks
if ($LASTEXITCODE) { throw 'Settings persistence regression failed' }
