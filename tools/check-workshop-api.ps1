$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/workshop-api-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# Pure Workshop client core (workshop + workshop.api) plus Gson; no Minecraft classes on the classpath on purpose.
$groups = @('com.google.code.gson')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$cp = (@($out) + @($jars)) -join [IO.Path]::PathSeparator
$jdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
$sources = @()
foreach ($name in @('package-info','WorkshopLayout','WorkshopSignature','BeatmapPackageException','BeatmapManifest','BeatmapPackage','WorkshopCatalog','ServerIdHash')) { $sources += "$root/src/main/java/ru/gora/musicbattle/workshop/$name.java" }
foreach ($api in @('package-info','AtomicFiles','MediaUrl','AutoSignInGate','CancelToken','ClientLabels','DeviceFlow','InstalledCheck','MinecraftSignIn','RefreshTokenStore','WorkshopApi','WorkshopApiException','WorkshopConfig','WorkshopDownloader','WorkshopDtos','WorkshopInbox','WorkshopRequest','WorkshopSession')) { $sources += "$root/src/main/java/ru/gora/musicbattle/workshop/api/$api.java" }
foreach ($support in @('FakeWorkshopServer','WorkshopFixtures')) { $sources += "$root/tools/workshop-check-support/$support.java" }
$sources += "$root/tools/WorkshopApiChecks.java"
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out @sources
if ($LASTEXITCODE) { throw 'Workshop API test compilation failed' }
& "$jdk/java.exe" -cp $cp WorkshopApiChecks $root
if ($LASTEXITCODE) { throw 'Workshop API regression failed' }
