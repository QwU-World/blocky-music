$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/speaker-shape-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','org.apache.commons','org.apache.maven','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -File -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$fastutil = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/it.unimi.dsi/fastutil/8.5.12') -Recurse -File -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
$cp = (@($out, "$root/build/classes/java/main", "$root/build/moddev/artifacts/neoforge-21.1.230-merged.jar", "$root/build/moddev/artifacts/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar", $fastutil) + @($jars)) -join [IO.Path]::PathSeparator
$jdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out "$root/tools/SpeakerShapeChecks.java" "$root/tools/SpeakerBlockLifecycleChecks.java" "$root/tools/SpeakerCombinedChecks.java"
if ($LASTEXITCODE) { throw 'Speaker shape checks compilation failed' }
& "$jdk/java.exe" -cp $cp SpeakerCombinedChecks
if ($LASTEXITCODE) { throw 'Speaker structure checks failed' }
