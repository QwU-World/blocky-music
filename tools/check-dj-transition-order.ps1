$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build/dj-transition-order-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dependencyDir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dependencyDir) { rg --files $dependencyDir -g '*.jar' | Where-Object { $_ -notmatch '(-sources|-javadoc|-natives-)' } }
}
$cp = (@($outputDir,"$projectDir/build/classes/java/main","$projectDir/build/moddev/artifacts/neoforge-21.1.230-merged.jar") + @($jars)) -join [IO.Path]::PathSeparator
$sources = @('tools/DjTransitionOrderChecks.java','src/main/java/ru/gora/musicbattle/client/ClientTrackEffects.java','src/main/java/ru/gora/musicbattle/client/ClientDjFxTransition.java','src/main/java/ru/gora/musicbattle/data/TrackEffect.java','src/main/java/ru/gora/musicbattle/data/DjFxRelease.java') | ForEach-Object { Join-Path $projectDir $_ }
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $outputDir @sources
if ($LASTEXITCODE) { throw 'DJ transition order checks compilation failed' }
& "$jdk/java.exe" -cp $cp DjTransitionOrderChecks
if ($LASTEXITCODE) { throw 'DJ transition order regression failed' }
