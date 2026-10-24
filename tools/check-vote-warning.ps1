$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/vote-warning-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$jars = @($jars | Where-Object { [IO.Path]::GetFileName($_) -ne 'fastutil-8.3.1.jar' })
$jars += @(rg --files (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.lwjgl') -g '*-3.3.3-natives-windows.jar')
$cp = (@($out, "$root/build/classes/java/main", "$root/build/moddev/artifacts/neoforge-21.1.230-merged.jar", "$root/build/moddev/artifacts/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar") + @($jars)) -join [IO.Path]::PathSeparator
$sources = @(
    "$root/tools/MusicBattleVoteWarningChecks.java",
    "$root/src/main/java/ru/gora/musicbattle/client/MusicBattleVoteScreen.java",
    "$root/src/main/java/ru/gora/musicbattle/client/ClientBattleSettings.java",
    "$root/src/main/java/ru/gora/musicbattle/client/ClientNoteAppearanceConfig.java",
    "$root/src/main/java/ru/gora/musicbattle/data/NoteAppearance.java",
    "$root/src/main/java/ru/gora/musicbattle/data/NoteSpeed.java",
    "$root/src/main/java/ru/gora/musicbattle/network/SettingsPayload.java",
    "$root/src/main/java/ru/gora/musicbattle/network/NoteAppearanceCodec.java"
)
$jdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out @sources
if ($LASTEXITCODE) { throw 'Vote warning test compilation failed' }
& "$jdk/java.exe" -Djava.awt.headless=true -cp $cp MusicBattleVoteWarningChecks $root
if ($LASTEXITCODE) { throw 'Vote warning regression failed' }
