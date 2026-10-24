$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectRoot 'build/note-ui-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$jdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
$sources = @((Join-Path $PSScriptRoot 'NoteUiChecks.java'))
foreach ($name in @('SettingsScreenLayout', 'BattleNoteShape')) {
    $source = Join-Path $projectRoot "src/main/java/ru/gora/musicbattle/client/$name.java"
    if (Test-Path -LiteralPath $source) { $sources += $source }
}
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -d $outputDir @sources
if ($LASTEXITCODE) { throw 'Note UI test compilation failed' }
& "$jdk/java.exe" -cp $outputDir ru.gora.musicbattle.client.NoteUiChecks
if ($LASTEXITCODE) { throw 'Note UI regression failed' }
