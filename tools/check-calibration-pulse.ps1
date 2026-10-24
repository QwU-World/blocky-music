$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build/calibration-pulse-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$javaDir = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin'
$sources = @((Join-Path $PSScriptRoot 'CalibrationPulseChecks.java'))
$helper = Join-Path $projectDir 'src/main/java/ru/gora/musicbattle/data/CalibrationPulse.java'
if (Test-Path -LiteralPath $helper) { $sources += $helper }
& (Join-Path $javaDir 'javac.exe') -encoding UTF-8 -d $outputDir @sources
if ($LASTEXITCODE -ne 0) { throw 'Calibration pulse test compile failed' }
& (Join-Path $javaDir 'java.exe') -cp $outputDir CalibrationPulseChecks
if ($LASTEXITCODE -ne 0) { throw 'Calibration pulse regression failed' }
