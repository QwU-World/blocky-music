$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/server-comments-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$jars = @($jars | Where-Object { [IO.Path]::GetFileName($_) -ne 'fastutil-8.3.1.jar' })
$cp = (@($out, "$root/build/classes/java/main", "$root/build/moddev/artifacts/neoforge-21.1.230-merged.jar") + @($jars)) -join [IO.Path]::PathSeparator
$jdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out "$root/tools/ServerCommentsChecks.java"
if ($LASTEXITCODE) { throw 'Server comments test compilation failed' }
& "$jdk/java.exe" -cp $cp ru.gora.musicbattle.server.workshop.ServerCommentsChecks $root
if ($LASTEXITCODE) { throw 'Server comments regression failed' }
