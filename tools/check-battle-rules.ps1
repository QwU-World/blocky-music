[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build\battle-rules-checks'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
# The stats store is the real class, run against a throwaway game folder (FMLPaths), so it needs the loader, Gson and
# the game jar for the types it names; the rules under test are compiled from their sources, not taken from a build.
$groups = @('com.google.code.gson','com.google.guava','com.mojang','it.unimi.dsi','org.apache.commons','org.slf4j','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$jars = @($jars | Where-Object { [IO.Path]::GetFileName($_) -ne 'fastutil-8.3.1.jar' })
$cp = (@($outputDir, "$projectDir/build/classes/java/main", "$projectDir/build/moddev/artifacts/neoforge-21.1.230-merged.jar") + @($jars)) -join [IO.Path]::PathSeparator
$javaDir = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
& (Join-Path $javaDir 'javac.exe') -proc:none -encoding UTF-8 -cp $cp -d $outputDir `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\data\BattleModeVote.java') `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\data\BattleWinMilestones.java') `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\data\VoteTiming.java') `
    (Join-Path $projectDir 'src\main\java\ru\gora\musicbattle\server\PlayerBattleStats.java') `
    (Join-Path $PSScriptRoot 'BattleRulesChecks.java')
if ($LASTEXITCODE -ne 0) { throw 'Battle rule checks failed to compile.' }
& (Join-Path $javaDir 'java.exe') -cp $cp BattleRulesChecks $projectDir
if ($LASTEXITCODE -ne 0) { throw 'Battle rule regression failed.' }
