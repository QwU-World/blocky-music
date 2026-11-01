$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectRoot 'build/note-settings-focus-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$groups = @('com.ibm.icu','com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $directory = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $directory) { rg --files $directory -g '*.jar' | Where-Object { [IO.Path]::GetFileName($_) -notmatch '(-sources|-javadoc|-natives-|fastutil-8.3.1)' } }
}
$cp = (@($outputDir,"$projectRoot/build/classes/java/main","$projectRoot/build/moddev/artifacts/neoforge-21.1.230-merged.jar") + @($jars)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
$sources = @("$projectRoot/tools/NoteSettingsFocusChecks.java", "$projectRoot/src/main/java/ru/gora/musicbattle/client/MusicBattleSettingsScreen.java")
foreach ($path in @('client/SettingsScreenLayout','client/SettingsPanelPreview','client/BattleBackdrop','client/BattlePanelRenderer','client/BattleNoteShape','client/ClientBattleSettings','client/ClientNoteAppearanceConfig','data/NoteAppearance','data/NoteSpeed','network/SettingsPayload','network/NoteAppearanceCodec')) {
    $sources += "$projectRoot/src/main/java/ru/gora/musicbattle/$path.java"
}
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $outputDir @sources
if ($LASTEXITCODE) { throw 'Note settings focus test compilation failed' }
& "$jdk/java.exe" -cp $cp ru.gora.musicbattle.client.NoteSettingsFocusChecks
if ($LASTEXITCODE) { throw 'Note settings focus regression failed' }
