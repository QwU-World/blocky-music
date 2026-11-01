$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/npc-config-layout-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# The test NPC's settings screen as pure geometry, plus Gson for the lang files; no Minecraft on the classpath,
# text is measured with java.awt.
$groups = @('com.google.code.gson')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$cp = (@($out) + @($jars)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
$sources = @("$root/src/main/java/ru/gora/musicbattle/client/RapGodConfigLayout.java")
foreach ($name in @('TextStyle','TextMeasurer','Palette')) { $sources += "$root/src/main/java/ru/gora/musicbattle/client/ui/$name.java" }
foreach ($name in @('Rect','TextFit','Metrics')) { $sources += "$root/src/main/java/ru/gora/musicbattle/client/workshop/layout/$name.java" }
$sources += "$root/tools/RapGodConfigLayoutChecks.java"
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out @sources
if ($LASTEXITCODE) { throw 'NPC settings layout test compilation failed' }
& "$jdk/java.exe" -Djava.awt.headless=true -cp $cp RapGodConfigLayoutChecks $root
if ($LASTEXITCODE) { throw 'NPC settings layout regression failed' }
