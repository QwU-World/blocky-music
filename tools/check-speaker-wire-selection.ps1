[CmdletBinding()]
param([switch]$Packaged)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root $(if ($Packaged) { 'build/speaker-wire-selection-packaged-checks' } else { 'build/speaker-wire-selection-checks' })
New-Item -ItemType Directory -Path $out -Force | Out-Null
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -File -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$modVersion = (Select-String -LiteralPath "$root/gradle.properties" -Pattern '^mod_version=(.+)$').Matches[0].Groups[1].Value.Trim()
$compiledProduction = if ($Packaged) { "$root/build/libs/blocky_music-$modVersion.jar" } else { "$root/build/classes/java/main" }
$cp = (@($out, $compiledProduction, "$root/build/moddev/artifacts/neoforge-21.1.230-merged.jar", "$root/build/moddev/artifacts/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar") + @($jars)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
$sources = @("$root/tools/SpeakerWireSelectionChecks.java")
$production = "$root/src/main/java/ru/gora/musicbattle/item/SpeakerWireSelection.java"
if (-not $Packaged -and (Test-Path -LiteralPath $production)) { $sources += $production }
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out @sources
if ($LASTEXITCODE) { throw 'Speaker wire selection checks compilation failed' }
& "$jdk/java.exe" -cp $cp ru.gora.musicbattle.item.SpeakerWireSelectionChecks
if ($LASTEXITCODE) { throw 'Speaker wire selection checks failed' }
