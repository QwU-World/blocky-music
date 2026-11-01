param([switch]$DataOnly,[switch]$Sync,[switch]$World)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$outputDir=Join-Path $projectRoot 'build/note-appearance-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$groups=@('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm','dev.kosmx.player-anim')
$jars=foreach($group in $groups){$dir=Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group";if(Test-Path -LiteralPath $dir){Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName}}
$nativeJars=@();if($World){$nativeJars=@(rg --files (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.lwjgl') -g '*natives-windows.jar' | Where-Object { [IO.Path]::GetFileName($_) -match '^lwjgl(-jemalloc)?-3\.3\.3-natives-windows\.jar$' })}
$cp=(@($outputDir,"$projectRoot/build/classes/java/main","$projectRoot/build/moddev/artifacts/neoforge-21.1.230-merged.jar")+@($jars)+$nativeJars)-join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
$sources=@("$projectRoot/tools/NoteAppearanceChecks.java")
if($World){$sources+="$projectRoot/tools/WorldNoteShapeChecks.java"}
foreach($name in @('data/NoteAppearance','data/NoteSpeed','network/NoteAppearanceCodec','network/SettingsPayload','network/BattleStartPayload','network/SpectatorBattlePayload','network/BattleAppearancePayload')){$source="$projectRoot/src/main/java/ru/gora/musicbattle/$name.java";if(Test-Path -LiteralPath $source){$sources+=$source}}
if($Sync -or $World){foreach($name in @('client/ClientBattleController','client/BattlePanelRenderer','client/BattleNoteShape','server/BattleManager','network/MusicBattleNetworking','network/ClientPayloadBridge')){$sources+="$projectRoot/src/main/java/ru/gora/musicbattle/$name.java"}}
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $outputDir @sources
if($LASTEXITCODE){throw 'Note appearance test compilation failed'}
$testArgs=@();if($DataOnly){$testArgs+='data'}elseif($Sync){$testArgs+='sync'}
& "$jdk/java.exe" -cp $cp NoteAppearanceChecks @testArgs
if($LASTEXITCODE){throw 'Note appearance regression failed'}
if($World){& "$jdk/java.exe" -cp $cp WorldNoteShapeChecks;if($LASTEXITCODE){throw 'World note geometry regression failed'}}
