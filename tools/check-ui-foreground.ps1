$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$dependencyRoot = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$lwjglJars = @(rg --files (Join-Path $dependencyRoot 'org.lwjgl') -g '*.jar' |
    Where-Object { [IO.Path]::GetFileName($_) -match '^lwjgl(-glfw|-opengl)?-3\.3\.3(-natives-windows)?\.jar$' })
if ($lwjglJars.Count -ne 6) { throw "Expected six LWJGL 3.3.3 Windows jars; found $($lwjglJars.Count)." }
$supportJars = foreach ($group in @('org.joml', 'com.google.guava', 'it.unimi.dsi', 'org.slf4j', 'org.apache.logging.log4j', 'com.mojang', 'com.google.code.gson', 'org.apache.commons')) {
    rg --files (Join-Path $dependencyRoot $group) -g '*.jar' |
        Where-Object { [IO.Path]::GetFileName($_) -notmatch '(-sources|-javadoc)' }
}
$outputDir = Join-Path $projectRoot 'build/ui-foreground-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$distApi = @(rg --files (Join-Path $dependencyRoot 'net.neoforged/mergetool') -g 'mergetool-2.0.3-api.jar')[0]
if (-not $distApi) { throw 'Cached NeoForge distribution annotation API is missing.' }
$testClasspath = (@($outputDir, "$projectRoot/build/moddev/artifacts/neoforge-21.1.230-merged.jar", $distApi) + $lwjglJars + @($supportJars)) -join [IO.Path]::PathSeparator
$sources = @((Join-Path $PSScriptRoot 'UiForegroundChecks.java'))
$helper = Join-Path $projectRoot 'src/main/java/ru/gora/musicbattle/client/ClientUiForeground.java'
if (Test-Path -LiteralPath $helper) { $sources += $helper }
$javaDir = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
& "$javaDir/javac.exe" -proc:none -encoding UTF-8 -cp $testClasspath -d $outputDir @sources
if ($LASTEXITCODE -ne 0) { throw 'UI foreground test compilation failed' }
& "$javaDir/java.exe" -cp $testClasspath UiForegroundChecks
if ($LASTEXITCODE -ne 0) { throw 'UI foreground regression failed' }
