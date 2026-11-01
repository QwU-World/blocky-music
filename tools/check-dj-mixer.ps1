$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectRoot 'build/dj-mixer-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dependencyDir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dependencyDir) { rg --files $dependencyDir -g '*.jar' | Where-Object { $_ -notmatch '(-sources|-javadoc|-natives-)' } }
}
$cp = (@($outputDir, "$projectRoot/build/classes/java/main", "$projectRoot/build/moddev/artifacts/neoforge-21.1.230-merged.jar") + @($jars)) -join [IO.Path]::PathSeparator
$sources = @('tools/DjMixerChecks.java','tools/dj-mixer-support/ru/gora/musicbattle/MusicBattleMod.java','src/main/java/ru/gora/musicbattle/data/TrackEffect.java','src/main/java/ru/gora/musicbattle/data/DjVisualEffects.java','src/main/java/ru/gora/musicbattle/client/ClientTrackEffects.java','src/main/java/ru/gora/musicbattle/client/DjVisualFxScreen.java','src/main/java/ru/gora/musicbattle/client/DjPanelScreen.java')
foreach ($optional in @('data/DjPlaylistMixer.java','data/DjPlaylistEntry.java','network/EditDjPlaylistMixerPayload.java')) {
    $path = "src/main/java/ru/gora/musicbattle/$optional"
    if (Test-Path (Join-Path $projectRoot $path)) { $sources += $path }
}
$sources = $sources | ForEach-Object { Join-Path $projectRoot $_ }
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $outputDir @sources
if ($LASTEXITCODE) { throw 'DJ mixer test compilation failed' }
& "$jdk/java.exe" -cp $cp DjMixerChecks
if ($LASTEXITCODE) { throw 'DJ mixer regression failed' }
