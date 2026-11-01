[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\battle-placement-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$javaDir = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
& (Join-Path $javaDir 'javac.exe') -encoding UTF-8 -d $outputDir `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\data\BattlePlacementMath.java') `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\data\BattleCameraShot.java') `
    (Join-Path $PSScriptRoot 'BattlePlacementChecks.java')
if ($LASTEXITCODE -ne 0) { throw 'Battle placement checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $outputDir BattlePlacementChecks
if ($LASTEXITCODE -ne 0) { throw 'Battle placement regression failed.' }
