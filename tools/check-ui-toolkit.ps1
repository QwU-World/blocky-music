$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/ui-toolkit-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# The pure parts of the Workshop UI toolkit plus Gson; Minecraft's jar only so Motion's reference to net.minecraft.Util resolves.
$groups = @('com.google.code.gson','com.google.guava','com.mojang','it.unimi.dsi','org.slf4j','org.apache.logging.log4j','org.jetbrains')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$cp = (@($out, "$root/build/moddev/artifacts/neoforge-21.1.230-merged.jar") + @($jars)) -join [IO.Path]::PathSeparator
$jdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
$sources = @()
foreach ($name in @('SdfMath','Spectrum','Palette','TextStyle','Motion','CardGrid','UiShaderGuard','UiIcon')) { $sources += "$root/src/main/java/ru/gora/musicbattle/client/ui/$name.java" }
$sources += "$root/tools/UiToolkitChecks.java"
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out @sources
if ($LASTEXITCODE) { throw 'UI toolkit test compilation failed' }
& "$jdk/java.exe" -Djava.awt.headless=true -cp $cp UiToolkitChecks $root
if ($LASTEXITCODE) { throw 'UI toolkit regression failed' }
