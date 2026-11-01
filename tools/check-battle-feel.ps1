[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\battle-feel-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$javaDir = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
& (Join-Path $javaDir 'javac.exe') -encoding UTF-8 -d $outputDir `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\data\BattleClock.java') `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\data\BattleSkipClock.java') `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\data\BattlePauseClock.java') `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\data\BattleCameraFocus.java') `
    (Join-Path $PSScriptRoot 'BattleFeelChecks.java')
if ($LASTEXITCODE -ne 0) { throw 'Battle feel checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $outputDir BattleFeelChecks
if ($LASTEXITCODE -ne 0) { throw 'Battle feel regression failed.' }
