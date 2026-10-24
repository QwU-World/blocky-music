[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\workshop-package-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
# The workshop package is pure java.nio/java.util.zip plus Gson; no Minecraft classes on the classpath on purpose.
$groups = @('com.google.code.gson')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$classPath = (@($outputDir) + @($jars)) -join [IO.Path]::PathSeparator
$javaDir = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin'
$sources = @('src\main\java\ru\gora\musicbattle\workshop\package-info.java',
    'src\main\java\ru\gora\musicbattle\workshop\WorkshopLayout.java',
    'src\main\java\ru\gora\musicbattle\workshop\WorkshopSignature.java',
    'src\main\java\ru\gora\musicbattle\workshop\BeatmapPackageException.java',
    'src\main\java\ru\gora\musicbattle\workshop\BeatmapManifest.java',
    'src\main\java\ru\gora\musicbattle\workshop\BeatmapPackage.java',
    'src\main\java\ru\gora\musicbattle\workshop\WorkshopCatalog.java',
    'tools\WorkshopPackageChecks.java') | ForEach-Object { Join-Path $projectDir $_ }
& (Join-Path $javaDir 'javac.exe') -proc:none -encoding UTF-8 -cp $classPath -d $outputDir @sources
if ($LASTEXITCODE -ne 0) { throw 'Workshop package checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $classPath WorkshopPackageChecks
if ($LASTEXITCODE -ne 0) { throw 'Workshop package regression failed.' }
