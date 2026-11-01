[CmdletBinding()]
param([switch]$Packaged)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\chart-offset-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$javaDir = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
# The first-beat offset rules, the real exporter and the real chart reader, old build against new; nothing here
# starts the game, and every chart is read from a temporary copy.
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$jars = @($jars | Where-Object { [IO.Path]::GetFileName($_) -ne 'fastutil-8.3.1.jar' })
$modVersion = (Select-String -LiteralPath "$projectDir/gradle.properties" -Pattern '^mod_version=(.+)$').Matches[0].Groups[1].Value.Trim()
$classes = if ($Packaged) { "$projectDir/build/libs/blocky_music-$modVersion.jar" } else { "$projectDir/build/classes/java/main" }
$cp = (@($outputDir, $classes, "$projectDir/build/moddev/artifacts/neoforge-21.1.230-merged.jar", "$projectDir/build/moddev/artifacts/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar") + @($jars)) -join [IO.Path]::PathSeparator
$sources = @(
    'tools/ChartOffsetChecks.java'
) | ForEach-Object { Join-Path $projectDir $_ }
& (Join-Path $javaDir 'javac.exe') -proc:none -encoding UTF-8 -cp $cp -d $outputDir @sources
if ($LASTEXITCODE -ne 0) { throw 'Chart offset checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $cp ChartOffsetChecks $projectDir
if ($LASTEXITCODE -ne 0) { throw 'Chart offset regression failed.' }
