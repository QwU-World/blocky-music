$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/workshop-auth-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# Pure Workshop client core (workshop + workshop.api) plus Gson; no Minecraft classes on the classpath on purpose.
$groups = @('com.google.code.gson')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$cp = (@($out) + @($jars)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
$sources = @()
foreach ($name in @('package-info','WorkshopLayout','WorkshopSignature','BeatmapPackageException','BeatmapManifest','BeatmapPackage','WorkshopCatalog','ServerIdHash')) { $sources += "$root/src/main/java/ru/gora/musicbattle/workshop/$name.java" }
foreach ($api in @('package-info','AtomicFiles','AutoSignInGate','CancelToken','ClientLabels','DeviceFlow','InstalledCheck','MinecraftSignIn','RefreshTokenStore','WorkshopApi','WorkshopApiException','WorkshopConfig','WorkshopDownloader','WorkshopDtos','WorkshopInbox','WorkshopRequest','WorkshopSession')) { $sources += "$root/src/main/java/ru/gora/musicbattle/workshop/api/$api.java" }
foreach ($support in @('FakeWorkshopServer','WorkshopFixtures')) { $sources += "$root/tools/workshop-check-support/$support.java" }
$sources += "$root/tools/WorkshopAuthChecks.java"
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out @sources
if ($LASTEXITCODE) { throw 'Workshop auth test compilation failed' }
& "$jdk/java.exe" -cp $cp WorkshopAuthChecks $root
if ($LASTEXITCODE) { throw 'Workshop auth regression failed' }
