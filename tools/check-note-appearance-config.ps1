$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectRoot 'build/note-appearance-config-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$gson = @(rg --files (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/com.google.code.gson') -g 'gson-*.jar' | Where-Object { [IO.Path]::GetFileName($_) -notmatch '(-sources|-javadoc)' })[0]
$cp = (@($outputDir,$gson) -join [IO.Path]::PathSeparator)
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
$sources = @("$projectRoot/tools/NoteAppearanceConfigChecks.java","$projectRoot/src/main/java/ru/gora/musicbattle/data/NoteAppearance.java")
$helper = "$projectRoot/src/main/java/ru/gora/musicbattle/client/ClientNoteAppearanceConfig.java"
if (Test-Path -LiteralPath $helper) { $sources += $helper }
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $outputDir @sources
if ($LASTEXITCODE) { throw 'Note config test compilation failed' }
& "$jdk/java.exe" -cp $cp ru.gora.musicbattle.client.NoteAppearanceConfigChecks
if ($LASTEXITCODE) { throw 'Note config regression failed' }
