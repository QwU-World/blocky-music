[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\vote-timing-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$javaDir = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
& (Join-Path $javaDir 'javac.exe') -encoding UTF-8 -d $outputDir `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\data\VoteTiming.java') `
    (Join-Path $PSScriptRoot 'VoteTimingChecks.java')
if ($LASTEXITCODE -ne 0) { throw 'Vote timing checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $outputDir VoteTimingChecks
if ($LASTEXITCODE -ne 0) { throw 'Vote timing regression failed.' }
