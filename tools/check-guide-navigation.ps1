$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/guide-navigation-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$cp = (@($out, "$root/build/classes/java/main", "$root/build/moddev/artifacts/neoforge-21.1.230-merged.jar", "$root/build/moddev/artifacts/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar") + @($jars)) -join [IO.Path]::PathSeparator
$jdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out "$root/tools/GuideNavigationChecks.java" "$root/src/main/java/ru/gora/musicbattle/client/MusicBattleGuideScreen.java" "$root/src/main/java/ru/gora/musicbattle/client/GuideLayout.java"
if ($LASTEXITCODE) { throw 'Guide navigation compilation failed' }
& "$jdk/java.exe" -cp $cp GuideNavigationChecks
if ($LASTEXITCODE) { throw 'Guide navigation regression failed' }
