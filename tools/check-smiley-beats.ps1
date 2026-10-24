[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\smiley-beat-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$javaDir = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin'
& (Join-Path $javaDir 'javac.exe') -encoding UTF-8 -d $outputDir `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\data\MusicBeatMath.java') `
    (Join-Path $PSScriptRoot 'SmileyBeatChecks.java')
if ($LASTEXITCODE -ne 0) { throw 'Smiley beat checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $outputDir SmileyBeatChecks
if ($LASTEXITCODE -ne 0) { throw 'Smiley beat regression failed.' }
