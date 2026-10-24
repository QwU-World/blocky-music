param([switch]$Packaged)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/client-defaults-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$jars = @($jars | Where-Object { [IO.Path]::GetFileName($_) -ne 'fastutil-8.3.1.jar' })
$jars += @(rg --files (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.lwjgl') -g '*-3.3.3-natives-windows.jar')
$classes = if ($Packaged) { "$root/build/libs/blocky_music-0.1.0.jar" } else { "$root/build/classes/java/main" }
$cp = (@($out, $classes, "$root/build/moddev/artifacts/neoforge-21.1.230-merged.jar", "$root/build/moddev/artifacts/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar") + @($jars)) -join [IO.Path]::PathSeparator
$jdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out "$root/tools/ClientDefaultsChecks.java"
if ($LASTEXITCODE) { throw 'Client defaults test compilation failed' }
$failed = @()
foreach ($mode in @('fresh','existing')) {
    & "$jdk/java.exe" -cp $cp ClientDefaultsChecks $mode
    if ($LASTEXITCODE) { $failed += $mode }
}
if ($failed.Count) { throw "Client defaults regression failed: $($failed -join ', ')" }
