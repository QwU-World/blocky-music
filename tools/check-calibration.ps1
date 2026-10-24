[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\calibration-checks'
$mappedJar = Join-Path $projectDir 'build\moddev\artifacts\neoforge-21.1.230-merged.jar'
if (!(Test-Path -LiteralPath $mappedJar)) { throw 'Compile the project once to obtain mapped Minecraft classes.' }
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$javaDir = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin'
& (Join-Path $javaDir 'javac.exe') -encoding UTF-8 -cp $mappedJar -d $outputDir `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\data\CalibrationTiming.java') `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\client\CalibratedAudioStream.java') `
    (Join-Path $PSScriptRoot 'CalibrationChecks.java')
if ($LASTEXITCODE -ne 0) { throw 'Calibration checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp ($outputDir + [IO.Path]::PathSeparator + $mappedJar) ru.gora.musicbattle.client.CalibrationChecks
if ($LASTEXITCODE -ne 0) { throw 'Calibration regression failed.' }
