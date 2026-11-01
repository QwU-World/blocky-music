[CmdletBinding()]
param([switch]$Packaged)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\beat-one-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$javaDir = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
# Every beat visual counts from the chart's beat 1: the real sampler against a build from before offsets at offset 0
# (BLOCKY_PREVIOUS_JAR; skipped without it), the offset rules, the wire and source rules for what needs a game. Nothing here starts the game.
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.apache.maven','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$jars = @($jars | Where-Object { [IO.Path]::GetFileName($_) -ne 'fastutil-8.3.1.jar' })
$modVersion = (Select-String -LiteralPath "$projectDir/gradle.properties" -Pattern '^mod_version=(.+)$').Matches[0].Groups[1].Value.Trim()
$classes = if ($Packaged) { "$projectDir/build/libs/blocky_music-$modVersion.jar" } else { "$projectDir/build/classes/java/main" }
$cp = (@($outputDir, $classes, "$projectDir/build/moddev/artifacts/neoforge-21.1.230-merged.jar", "$projectDir/build/moddev/artifacts/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar") + @($jars)) -join [IO.Path]::PathSeparator
$sources = @(
    'tools/BeatOneChecks.java'
) | ForEach-Object { Join-Path $projectDir $_ }
& (Join-Path $javaDir 'javac.exe') -proc:none -encoding UTF-8 -cp $cp -d $outputDir @sources
if ($LASTEXITCODE -ne 0) { throw 'Beat-one checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $cp BeatOneChecks $projectDir
if ($LASTEXITCODE -ne 0) { throw 'Beat-one regression failed.' }
