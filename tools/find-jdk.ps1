# The bin folder of a JDK 21 for the tools/*.ps1 scripts, which call it as
#   $jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
# It looks where tools/run-check.py looks, in the same order: BLOCKY_JDK, then JAVA_HOME (each the JDK's own folder,
# the one that holds bin), then the javac.exe on PATH. The first one that is Java 21 wins; when none is, it stops and
# lists what it found.
$ErrorActionPreference = 'Continue' # `java -version` writes to stderr, which 'Stop' would turn into an error

$candidates = @()
if ($env:BLOCKY_JDK) { $candidates += , @('BLOCKY_JDK', (Join-Path $env:BLOCKY_JDK 'bin')) }
if ($env:JAVA_HOME) { $candidates += , @('JAVA_HOME', (Join-Path $env:JAVA_HOME 'bin')) }
$onPath = Get-Command 'javac.exe' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -ne $onPath) { $candidates += , @('PATH', (Split-Path -Parent $onPath.Source)) }

$seen = @()
foreach ($candidate in $candidates) {
    $source = $candidate[0]
    $bin = $candidate[1]
    $java = Join-Path $bin 'java.exe'
    if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or -not (Test-Path -LiteralPath (Join-Path $bin 'javac.exe') -PathType Leaf)) {
        $seen += "${source}: $bin (no java.exe and javac.exe)"
        continue
    }
    $version = (& $java -version 2>&1 | Out-String)
    $major = if ($version -match 'version "(\d+)') { [int]$Matches[1] } else { 0 }
    if ($major -eq 21) {
        return $bin
    }
    $seen += "${source}: $bin (Java $major)"
}
$found = if ($seen.Count -gt 0) { ' (found only ' + ($seen -join '; ') + ')' } else { '' }
throw ("No JDK 21 found$found. Set BLOCKY_JDK or JAVA_HOME to a JDK 21, or put its bin folder on PATH.")
