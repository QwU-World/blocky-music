[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\smiley-sound-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi',
    'org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader',
    'net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$dependencyJars = foreach ($group in $groups) {
    $groupDir = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\$group"
    if (Test-Path -LiteralPath $groupDir) {
        Get-ChildItem -LiteralPath $groupDir -Recurse -File -Filter '*.jar' |
            Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName
    }
}
$classPath = (@($outputDir, "$projectDir\build\classes\java\main",
    "$projectDir\build\moddev\artifacts\neoforge-21.1.230-merged.jar") + @($dependencyJars)) -join [IO.Path]::PathSeparator
$javaDir = & (Join-Path $PSScriptRoot 'find-jdk.ps1')

& (Join-Path $javaDir 'javac.exe') -proc:none -encoding UTF-8 -cp $classPath -d $outputDir `
    (Join-Path $PSScriptRoot 'SmileySoundChecks.java') (Join-Path $PSScriptRoot 'SmileySoundAssetCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'Smiley sound checks failed to compile.' }

# The sound overrides, then the shipped smiley sound itself, read by the mod's own Ogg Vorbis decoder (no ffmpeg).
$failed = $false
foreach ($main in @('SmileySoundChecks','SmileySoundAssetCheck')) {
    & (Join-Path $javaDir 'java.exe') -cp $classPath $main $projectDir
    if ($LASTEXITCODE -ne 0) { $failed = $true }
}

if ($failed) { throw 'Smiley sound regression failed.' }
Write-Host 'Smiley sound regression PASS'
