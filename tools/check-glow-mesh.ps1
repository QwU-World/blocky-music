[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build/glow-mesh-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$javaDir = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
$sources = @((Join-Path $PSScriptRoot 'GlowMeshChecks.java'))
$helper = Join-Path $projectDir 'src/main/java/ru/gora/musicbattle/client/GlowMeshExpansion.java'
if (Test-Path -LiteralPath $helper) { $sources += $helper }
& (Join-Path $javaDir 'javac.exe') -encoding UTF-8 -d $outputDir @sources
if ($LASTEXITCODE -ne 0) { throw 'Glow mesh checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $outputDir GlowMeshChecks
if ($LASTEXITCODE -ne 0) { throw 'Glow mesh regression failed.' }
