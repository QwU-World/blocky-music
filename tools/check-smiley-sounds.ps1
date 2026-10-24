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
$javaDir = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin'

& (Join-Path $javaDir 'javac.exe') -proc:none -encoding UTF-8 -cp $classPath -d $outputDir `
    (Join-Path $PSScriptRoot 'SmileySoundChecks.java')
if ($LASTEXITCODE -ne 0) { throw 'Smiley sound checks failed to compile.' }

$failed = $false
& (Join-Path $javaDir 'java.exe') -cp $classPath SmileySoundChecks $projectDir
if ($LASTEXITCODE -ne 0) { $failed = $true }

$asset = Join-Path $projectDir 'src\main\resources\assets\music_battle\sounds\entity\smiley_damage.ogg'
$ffprobe = Get-Command ffprobe -ErrorAction Stop
$ffmpeg = Get-Command ffmpeg -ErrorAction Stop
if (-not (Test-Path -LiteralPath $asset -PathType Leaf)) {
    Write-Error "Missing smiley sound asset: $asset" -ErrorAction Continue
    $failed = $true
} else {
    $probe = & $ffprobe.Source -v error -select_streams a:0 -show_entries stream=codec_name,channels,sample_rate `
        -of json $asset | ConvertFrom-Json
    $stream = @($probe.streams)[0]
    if ($stream.codec_name -ne 'vorbis' -or [int]$stream.channels -ne 1) {
        Write-Error "Expected mono Ogg Vorbis, got codec=$($stream.codec_name), channels=$($stream.channels)." -ErrorAction Continue
        $failed = $true
    }
    & $ffmpeg.Source -v error -i $asset -f null NUL
    if ($LASTEXITCODE -ne 0) {
        Write-Error 'ffmpeg could not decode the complete smiley sound asset.' -ErrorAction Continue
        $failed = $true
    } else {
        Write-Host "Smiley OGG decode PASS: Vorbis, mono, $($stream.sample_rate) Hz"
    }
}

if ($failed) { throw 'Smiley sound regression failed.' }
Write-Host 'Smiley sound regression PASS'
