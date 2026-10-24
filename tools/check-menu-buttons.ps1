[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\menu-buttons-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$javaDir = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin'
& (Join-Path $javaDir 'javac.exe') -encoding UTF-8 -d $outputDir `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\client\workshop\layout\Rect.java') `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\client\workshop\layout\MenuCornerButtons.java') `
    (Join-Path $PSScriptRoot 'MenuButtonsChecks.java')
if ($LASTEXITCODE -ne 0) { throw 'Menu button checks failed to compile.' }
Push-Location $projectDir
try {
    & (Join-Path $javaDir 'java.exe') -cp $outputDir MenuButtonsChecks
    if ($LASTEXITCODE -ne 0) { throw 'Menu button regression failed.' }
} finally {
    Pop-Location
}
