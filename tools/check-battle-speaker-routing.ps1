$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectRoot 'build/battle-speaker-routing-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$compiled = Join-Path $projectRoot 'build/classes/java/main'
$cp = (@($outputDir, $compiled, "$projectRoot/build/moddev/artifacts/neoforge-21.1.230-merged.jar") + @($jars)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $outputDir "$projectRoot/tools/BattleSpeakerRoutingChecks.java"
if ($LASTEXITCODE) { throw 'Speaker routing test compilation failed' }
# Isolates the battle selector and then runs the checks against it in the same JVM (see BattleSpeakerRoutingChecks#main).
& "$jdk/java.exe" -cp $cp BattleSpeakerRoutingChecks $compiled $outputDir
if ($LASTEXITCODE) { throw 'Battle speaker routing regression failed' }
