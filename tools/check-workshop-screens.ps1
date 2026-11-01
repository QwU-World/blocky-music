$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/workshop-screens-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# The pure Workshop layout model plus Gson; no Minecraft on the classpath, text is measured with java.awt.
$groups = @('com.google.code.gson')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$cp = (@($out) + @($jars)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
$sources = @()
foreach ($name in @('TextStyle','TextMeasurer','Palette','Spectrum','CardGrid')) { $sources += "$root/src/main/java/ru/gora/musicbattle/client/ui/$name.java" }
foreach ($name in @('Rect','Tr','TextFit','Layout','Metrics','WorkshopStrings','Filters','ShellLayout','ListingLayout','CardLayout','SetPageLayout','CommentsLayout','Form','FormLayout','AccountForm','HtmlText')) { $sources += "$root/src/main/java/ru/gora/musicbattle/client/workshop/layout/$name.java" }
$sources += "$root/tools/WorkshopScreensChecks.java"
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out @sources
if ($LASTEXITCODE) { throw 'Workshop screens test compilation failed' }
& "$jdk/java.exe" -Djava.awt.headless=true -cp $cp WorkshopScreensChecks $root
if ($LASTEXITCODE) { throw 'Workshop screens regression failed' }
