[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\battle-skip-sync-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
# Minecraft's AudioStream comes from the merged game jar; the stream under test is compiled from its source.
$cp = (@($outputDir, (Join-Path $projectDir 'build\moddev\artifacts\neoforge-21.1.230-merged.jar'))) -join [IO.Path]::PathSeparator
$javaDir = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
& (Join-Path $javaDir 'javac.exe') -encoding UTF-8 -cp $cp -d $outputDir `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\data\BattleClock.java') `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\data\BattlePauseClock.java') `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\data\BattleSkipClock.java') `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\data\MusicStartAlignment.java') `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\client\AlignedStartAudioStream.java') `
    (Join-Path $PSScriptRoot 'BattleSkipSyncChecks.java')
if ($LASTEXITCODE -ne 0) { throw 'Battle skip sync checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $cp BattleSkipSyncChecks
if ($LASTEXITCODE -ne 0) { throw 'Battle skip sync regression failed.' }
