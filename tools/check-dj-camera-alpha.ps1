param(
    # A beatmap whose effects start with a fish_eye and a vignette at tick 0; by default the hand-written fixture.
    [string]$TrackPath = ''
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $TrackPath) { $TrackPath = Join-Path $projectRoot 'tools/fixtures/charts/offset-current.json' }
$dependencyRoot = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$lwjglJars = @(rg --files (Join-Path $dependencyRoot 'org.lwjgl') -g '*.jar' |
    Where-Object { [IO.Path]::GetFileName($_) -match '^lwjgl(-glfw|-opengl)?-3\.3\.3(-natives-windows)?\.jar$' })
if ($lwjglJars.Count -ne 6) { throw "Expected six cached LWJGL 3.3.3 Windows x64 jars; found $($lwjglJars.Count)." }
$gsonJar = @(rg --files (Join-Path $dependencyRoot 'com.google.code.gson/gson') -g 'gson-2.10.1.jar')[0]
if (-not $gsonJar) { throw 'Cached Gson 2.10.1 jar is missing.' }
$testClasspath = ($lwjglJars + $gsonJar) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
& "$jdk/java.exe" --class-path $testClasspath (Join-Path $PSScriptRoot 'DjCameraAlphaChecks.java') $projectRoot $TrackPath
if ($LASTEXITCODE -ne 0) { throw "DJ camera alpha regression failed with exit code $LASTEXITCODE." }
