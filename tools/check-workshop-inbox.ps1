$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/workshop-inbox-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# The inbox and the install requests the QwU Workshop Helper writes: pure java.nio plus Gson, no Minecraft classes
# on the classpath on purpose (spec 07 section 8, wave I1).
$groups = @('com.google.code.gson')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$cp = (@($out) + @($jars)) -join [IO.Path]::PathSeparator
$jdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
$sources = @()
foreach ($name in @('package-info','WorkshopLayout')) { $sources += "$root/src/main/java/ru/gora/musicbattle/workshop/$name.java" }
foreach ($api in @('package-info','AtomicFiles','WorkshopInbox','WorkshopRequest')) { $sources += "$root/src/main/java/ru/gora/musicbattle/workshop/api/$api.java" }
$sources += "$root/tools/WorkshopInboxChecks.java"
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out @sources
if ($LASTEXITCODE) { throw 'Workshop inbox test compilation failed' }
& "$jdk/java.exe" -cp $cp WorkshopInboxChecks
if ($LASTEXITCODE) { throw 'Workshop inbox regression failed' }
