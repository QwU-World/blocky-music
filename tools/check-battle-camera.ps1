[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\battle-camera-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$javaDir = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
& (Join-Path $javaDir 'javac.exe') -encoding UTF-8 -d $outputDir `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\data\BattleCameraShot.java') `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\data\BattleCameraFocus.java') `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\client\BattleCameraInterpolation.java') `
    (Join-Path $PSScriptRoot 'BattleCameraChecks.java')
if ($LASTEXITCODE -ne 0) { throw 'Battle camera checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $outputDir BattleCameraChecks
if ($LASTEXITCODE -ne 0) { throw 'Battle camera regression failed.' }
