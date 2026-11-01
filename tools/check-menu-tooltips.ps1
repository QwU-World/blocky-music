$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/menu-tooltips-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# The editor and the menu buttons come from the compiled classes; the resources jar carries vanilla's language file,
# which Language loads before anything is translated. No Minecraft window.
$groups = @('com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-)' | Select-Object -ExpandProperty FullName }
}
$cp = (@($out, "$root/build/classes/java/main", "$root/build/moddev/artifacts/neoforge-21.1.230-merged.jar", "$root/build/moddev/artifacts/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar") + @($jars)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out "$root/tools/MenuTooltipsChecks.java"
if ($LASTEXITCODE) { throw 'Menu tooltips test compilation failed' }
& "$jdk/java.exe" -Djava.awt.headless=true -cp $cp ru.gora.musicbattle.client.workshop.MenuTooltipsChecks $root
if ($LASTEXITCODE) { throw 'Menu tooltips regression failed' }
