[CmdletBinding()]
param([switch]$Packaged)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root $(if ($Packaged) { 'build/dj-cable-packaged-checks' } else { 'build/dj-cable-checks' })
New-Item -ItemType Directory -Path $out -Force | Out-Null
$groups = @('com.google.code.gson','com.google.guava','com.mojang','com.ibm.icu','io.netty','org.apache.commons','org.apache.maven','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -File -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$fastutil = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/it.unimi.dsi/fastutil/8.5.12') -Recurse -File -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
$production = if ($Packaged) { "$root/build/libs/blocky_music-0.1.0.jar" } else { "$root/build/classes/java/main" }
$cp = (@($out, $production, "$root/build/moddev/artifacts/neoforge-21.1.230-merged.jar", "$root/build/moddev/artifacts/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar", $fastutil) + @($jars)) -join [IO.Path]::PathSeparator
$jdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
$sources = @("$root/tools/animation-check-support/ru/gora/musicbattle/MusicBattleMod.java", "$root/tools/speaker-model-support/net/minecraft/client/renderer/RenderType.java", "$root/tools/DjCableModelChecks.java", "$root/tools/DjPanelShapeChecks.java")
if (-not $Packaged) {
    $sources += "$root/src/main/java/ru/gora/musicbattle/client/BattleCameraModel.java"
    $egg = "$root/src/main/java/ru/gora/musicbattle/item/SmileyBallSpawnEggItem.java"
    if (Test-Path -LiteralPath $egg) { $sources += $egg }
}
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out @sources
if ($LASTEXITCODE) { throw 'DJ/cable model checks compilation failed' }
$arguments = @($root)
if ($Packaged) { $arguments += 'packaged' }
& "$jdk/java.exe" -cp $cp ru.gora.musicbattle.client.DjCableModelChecks @arguments
if ($LASTEXITCODE) { throw 'DJ/cable model checks failed' }
& "$jdk/java.exe" -cp $cp DjPanelShapeChecks
if ($LASTEXITCODE) { throw 'DJ panel shape checks failed' }
