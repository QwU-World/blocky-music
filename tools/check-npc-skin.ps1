$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/npc-skin-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# The test NPC's skin from a file: the PNG rules the server keeps a skin by, the game's own PNG writer (LWJGL STB,
# hence the natives), the 64x32 conversion against vanilla's HttpTexture, the upload payload and the refusals.
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','commons-io','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc)' | Select-Object -ExpandProperty FullName }
}
$cp = (@($out, "$root/build/classes/java/main", "$root/build/moddev/artifacts/neoforge-21.1.230-merged.jar") + @($jars)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out "$root/tools/NpcSkinChecks.java"
if ($LASTEXITCODE) { throw 'NPC skin test compilation failed' }
& "$jdk/java.exe" -Djava.awt.headless=true -cp $cp NpcSkinChecks $root
if ($LASTEXITCODE) { throw 'NPC skin regression failed' }
