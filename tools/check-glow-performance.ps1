[CmdletBinding()]
param([switch]$Baseline)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build/glow-performance-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm','dev.kosmx.player-anim')
$dependencyRoot = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$jars = foreach ($group in $groups) {
    $dir = Join-Path $dependencyRoot $group
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$lwjgl = @(rg --files (Join-Path $dependencyRoot 'org.lwjgl') -g '*.jar' | Where-Object { [IO.Path]::GetFileName($_) -match '^lwjgl(-glfw|-opengl)?-3\.3\.3(-natives-windows)?\.jar$' })
$cp = (@($outputDir,"$projectDir/build/classes/java/main", "$projectDir/build/moddev/artifacts/neoforge-21.1.230-merged.jar") + @($jars) + $lwjgl) -join [IO.Path]::PathSeparator
$jdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $outputDir "$projectDir/src/main/java/ru/gora/musicbattle/client/GlowMeshExpansion.java" "$projectDir/src/main/java/ru/gora/musicbattle/client/ClientModelGlowEngine.java" "$PSScriptRoot/GlowMeshPerformanceChecks.java"
if ($LASTEXITCODE) { throw 'Glow performance check compilation failed' }
$checkArgs = @()
if ($Baseline) { $checkArgs += '--baseline' }
& "$jdk/java.exe" -Xms256m -Xmx256m -cp $cp GlowMeshPerformanceChecks @checkArgs
if ($LASTEXITCODE) { throw 'Glow performance regression failed' }
