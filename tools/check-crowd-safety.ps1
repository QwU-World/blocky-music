[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build/crowd-safety-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$javaDir = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
$sources = @((Join-Path $projectDir 'src/main/java/ru/gora/musicbattle/data/BattlePlacementMath.java'), (Join-Path $PSScriptRoot 'CrowdSafetyChecks.java'))
$policy = Join-Path $projectDir 'src/main/java/ru/gora/musicbattle/data/CrowdSafetyMath.java'
if (Test-Path -LiteralPath $policy) { $sources += $policy }
& (Join-Path $javaDir 'javac.exe') -encoding UTF-8 -d $outputDir @sources
if ($LASTEXITCODE -ne 0) { throw 'Crowd safety checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $outputDir CrowdSafetyChecks
if ($LASTEXITCODE -ne 0) { throw 'Crowd safety regression failed.' }
