$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build/color-picker-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$javaDir = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
$sources = @((Join-Path $PSScriptRoot 'ColorPickerChecks.java'))
$helper = Join-Path $projectDir 'src/main/java/ru/gora/musicbattle/data/ColorPickerMath.java'
if (Test-Path -LiteralPath $helper) { $sources += $helper }
& (Join-Path $javaDir 'javac.exe') -encoding UTF-8 -d $outputDir @sources
if ($LASTEXITCODE -ne 0) { throw 'Color picker check compile failed' }
& (Join-Path $javaDir 'java.exe') -cp $outputDir ColorPickerChecks
if ($LASTEXITCODE -ne 0) { throw 'Color picker checks failed' }
