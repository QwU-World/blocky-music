param([ValidateSet('All','Gpu','Navigation')][string]$Mode='All', [switch]$Packaged)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$outputDir=Join-Path $projectRoot $(if($Packaged) { 'build/note-visibility-packaged-checks' } else { 'build/note-visibility-checks' })
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$groups=@('com.ibm.icu','com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars=foreach($group in $groups) {
    $dir=Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if(Test-Path -LiteralPath $dir) { rg --files $dir -g '*.jar' | Where-Object { [IO.Path]::GetFileName($_) -notmatch '(-sources|-javadoc|-natives-|fastutil-8.3.1)' } }
}
$jars+=@(rg --files (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.lwjgl') -g '*-3.3.3-natives-windows.jar')
$modVersion = (Select-String -LiteralPath "$projectRoot/gradle.properties" -Pattern '^mod_version=(.+)$').Matches[0].Groups[1].Value.Trim()
$production=if($Packaged) { "$projectRoot/build/libs/blocky_music-$modVersion.jar" } else { "$projectRoot/build/classes/java/main" }
$cp=(@($outputDir,$production,"$projectRoot/build/moddev/artifacts/neoforge-21.1.230-merged.jar",
    "$projectRoot/build/moddev/artifacts/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar")+@($jars))-join [IO.Path]::PathSeparator
# Real RenderType initializes ItemRenderer/registries before NeoForge bootstrap. Replace only
# its GUI token, keeping real production vertex emission, PoseStack and GPU culling below it.
$sources=@("$projectRoot/tools/NotePanelGpuChecks.java","$projectRoot/tools/NoteSettingsNavigationChecks.java",
    "$projectRoot/tools/note-render-support/net/minecraft/client/renderer/RenderType.java")
if(-not $Packaged) {
    foreach($name in @('client/BattlePanelRenderer','client/BattleNoteShape','client/MusicBattleSettingsScreen','client/SettingsScreenLayout','client/SettingsPanelPreview')) {
        $sources+="$projectRoot/src/main/java/ru/gora/musicbattle/$name.java"
    }
}
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $outputDir @sources
if($LASTEXITCODE) { throw 'Note visibility test compilation failed' }
if($Mode -in @('All','Gpu')) {
    & "$jdk/java.exe" -cp $cp ru.gora.musicbattle.client.NotePanelGpuChecks
    if($LASTEXITCODE) { throw 'Note GPU visibility regression failed' }
}
if($Mode -in @('All','Navigation')) {
    & "$jdk/java.exe" -cp $cp ru.gora.musicbattle.client.NoteSettingsNavigationChecks $projectRoot
    if($LASTEXITCODE) { throw 'Note settings navigation regression failed' }
}
