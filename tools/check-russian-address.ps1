$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/russian-address-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# The Russian language file says «ты» to the player everywhere. Only Gson is needed: the file is read as it is.
$groups = @('com.google.code.gson')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc)' | Select-Object -ExpandProperty FullName }
}
$cp = (@($out) + @($jars)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out "$root/tools/RussianAddressChecks.java"
if ($LASTEXITCODE) { throw 'Russian address test compilation failed' }
& "$jdk/java.exe" -cp $cp RussianAddressChecks $root
if ($LASTEXITCODE) { throw 'Russian address regression failed' }
