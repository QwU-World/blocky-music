[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build/fx-audible-area-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$javaDir = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
# FxAudibleArea is plain Java on purpose, so the area math compiles and runs here without Minecraft.
$sources = @((Join-Path $PSScriptRoot 'FxAudibleAreaChecks.java'), (Join-Path $projectDir 'src/main/java/ru/gora/musicbattle/client/FxAudibleArea.java'))
& (Join-Path $javaDir 'javac.exe') -encoding UTF-8 -d $outputDir @sources
if ($LASTEXITCODE -ne 0) { throw 'FX audible area checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $outputDir FxAudibleAreaChecks $projectDir
if ($LASTEXITCODE -ne 0) { throw 'FX audible area regression failed.' }
