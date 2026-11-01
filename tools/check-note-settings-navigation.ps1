$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/note-settings-navigation-checks'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# The settings navigation half of check-note-visibility.ps1 on its own: the five tabs, both languages, and the
# dimming and bar controls. The project root locates the lang files and the bundled fonts.
$groups = @('com.ibm.icu','com.google.code.gson','com.google.guava','com.mojang','io.netty','it.unimi.dsi','org.apache.commons','org.slf4j','org.joml','org.lwjgl','net.neoforged.fancymodloader','net.neoforged','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm')
$jars = foreach ($group in $groups) {
    $dir = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$group"
    if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' | Where-Object Name -notmatch '(-sources|-javadoc|-natives-|fastutil-8.3.1)' | Select-Object -ExpandProperty FullName }
}
$cp = (@($out, "$root/build/classes/java/main", "$root/build/moddev/artifacts/neoforge-21.1.230-merged.jar", "$root/build/moddev/artifacts/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar") + @($jars)) -join [IO.Path]::PathSeparator
$jdk = & (Join-Path $PSScriptRoot 'find-jdk.ps1')
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out "$root/tools/NoteSettingsNavigationChecks.java"
if ($LASTEXITCODE) { throw 'Note settings navigation test compilation failed' }
& "$jdk/java.exe" -cp $cp ru.gora.musicbattle.client.NoteSettingsNavigationChecks $root
if ($LASTEXITCODE) { throw 'Note settings navigation regression failed' }
