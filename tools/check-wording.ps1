$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/wording-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# The words for a beatmap and its song in both languages, the /musicbattle beatmaps command and the editor's status lines. Only Gson is needed.
$groups = @('com.google.code.gson')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc)' | Select-Object -ExpandProperty FullName }
}
$cp = (@($out) + @($jars)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out "$root/tools/WordingChecks.java"
if ($LASTEXITCODE) { throw 'Wording test compilation failed' }
& "$jdk/java.exe" -cp $cp WordingChecks $root
if ($LASTEXITCODE) { throw 'Wording regression failed' }
