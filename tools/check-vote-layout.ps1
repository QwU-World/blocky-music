$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/vote-layout-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# The pure battle setup model, the picker's track list and cards, plus Gson; no Minecraft on the classpath,
# text is measured with java.awt.
$groups = @('com.google.code.gson')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$cp = (@($out) + @($jars)) -join [IO.Path]::PathSeparator
$jdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
$sources = @("$root/src/main/java/ru/gora/musicbattle/data/VoteTiming.java")
foreach ($name in @('TextStyle','TextMeasurer','Palette')) { $sources += "$root/src/main/java/ru/gora/musicbattle/client/ui/$name.java" }
foreach ($name in @('Rect','Tr','TextFit','Layout','Metrics','WorkshopStrings','Filters','ShellLayout','ListingLayout','CardLayout','BattleSetup','BattleSetupLayout','BattleTracks','TrackCardLayout')) { $sources += "$root/src/main/java/ru/gora/musicbattle/client/workshop/layout/$name.java" }
$sources += "$root/src/main/java/ru/gora/musicbattle/workshop/WorkshopLayout.java"
$sources += "$root/tools/VoteLayoutChecks.java"
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out @sources
if ($LASTEXITCODE) { throw 'Vote layout test compilation failed' }
& "$jdk/java.exe" -Djava.awt.headless=true -cp $cp VoteLayoutChecks $root
if ($LASTEXITCODE) { throw 'Vote layout regression failed' }
