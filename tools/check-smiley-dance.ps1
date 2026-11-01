[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\smiley-dance-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$javaDir = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
& (Join-Path $javaDir 'javac.exe') -encoding UTF-8 -d $outputDir `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\data\MusicBeatMath.java') `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\data\SmileyDancePattern.java') `
    (Join-Path $PSScriptRoot 'SmileyDanceChecks.java')
if ($LASTEXITCODE -ne 0) { throw 'Smiley dance checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $outputDir SmileyDanceChecks
if ($LASTEXITCODE -ne 0) { throw 'Smiley dance regression failed.' }
