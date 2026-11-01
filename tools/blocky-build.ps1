[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('Compile', 'Check', 'Build', 'Deploy', 'Full', 'FullDeploy')]
    [string]$Mode = 'Compile',

    # Deploy and FullDeploy install the jar here: the mods folder of the Minecraft instance you test with.
    [string]$ModsDir = $env:BLOCKY_MODS_DIR
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$propertiesPath = Join-Path $projectRoot 'gradle.properties'

function Get-GradleCommand {
    $wrapper = Join-Path $projectRoot 'gradlew.bat'
    if (Test-Path -LiteralPath $wrapper -PathType Leaf) {
        return $wrapper
    }

    $command = Get-Command 'gradle.bat' -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }

    throw 'Gradle was not found: there is no gradlew.bat in the project and no gradle.bat on PATH.'
}

function Get-ProjectProperty {
    param([Parameter(Mandatory = $true)][string]$Name)

    $line = Get-Content -LiteralPath $propertiesPath |
        Where-Object { $_ -match ('^' + [regex]::Escape($Name) + '=') } |
        Select-Object -First 1
    if ($null -eq $line) {
        throw "Missing project property: $Name"
    }
    return ($line -split '=', 2)[1].Trim()
}

function Test-MinecraftRunning {
    $processes = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -in @('java.exe', 'javaw.exe') -and
            $_.CommandLine -match 'net\.minecraft\.client\.main\.Main|minecraft|Modrinth'
        })
    return $processes.Count -gt 0
}

function Invoke-Gradle {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $gradle = Get-GradleCommand
    Write-Host "Gradle: $gradle"
    Write-Host "Task: $($Arguments -join ' ')"

    Push-Location $projectRoot
    try {
        & $gradle @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }
}

function Install-BuiltJar {
    $resolvedModsDir = (Resolve-Path -LiteralPath $ModsDir -ErrorAction Stop).Path
    $pathRoot = [System.IO.Path]::GetPathRoot($resolvedModsDir)
    if ($resolvedModsDir.TrimEnd('\') -eq $pathRoot.TrimEnd('\')) {
        throw "Refusing to deploy into a drive root: $resolvedModsDir"
    }

    if (Test-MinecraftRunning) {
        throw 'JAR is built, but deployment is blocked while Minecraft is running. Close Minecraft and run Deploy again.'
    }

    $version = Get-ProjectProperty -Name 'mod_version'
    $sourceJar = Join-Path $projectRoot "build\libs\blocky_music-$version.jar"
    if (-not (Test-Path -LiteralPath $sourceJar -PathType Leaf)) {
        throw "Built JAR is missing: $sourceJar"
    }

    $targetName = "blocky_music-$version-original.jar"
    $targetJar = Join-Path $resolvedModsDir $targetName
    $enabledJars = @(Get-ChildItem -LiteralPath $resolvedModsDir -File |
        Where-Object { $_.Name -like 'blocky_music*.jar' })
    $unexpectedJars = @($enabledJars | Where-Object { $_.Name -ne $targetName })
    if ($unexpectedJars.Count -gt 0) {
        $names = $unexpectedJars.Name -join ', '
        throw "Another Blocky Music JAR is enabled ($names). Switch back to ORIGINAL before deployment."
    }
    if ($enabledJars.Count -gt 1) {
        throw 'More than one Blocky Music JAR is enabled. Deployment was cancelled.'
    }

    $backupDir = Join-Path $resolvedModsDir 'backups'
    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null

    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $pendingJar = Join-Path $resolvedModsDir ".$targetName.$stamp.$PID.pending"
    $backupJar = Join-Path $backupDir "$targetName.before-deploy-$stamp.jar.bak"
    $sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourceJar).Hash

    Copy-Item -LiteralPath $sourceJar -Destination $pendingJar
    $pendingHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $pendingJar).Hash
    if ($pendingHash -ne $sourceHash) {
        $failedCopy = Join-Path $backupDir "$targetName.failed-copy-$stamp.jar"
        Move-Item -LiteralPath $pendingJar -Destination $failedCopy
        throw "Pending JAR hash mismatch. The failed copy was preserved at $failedCopy"
    }

    $oldHash = $null
    if (Test-Path -LiteralPath $targetJar -PathType Leaf) {
        $oldHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $targetJar).Hash
        try {
            [System.IO.File]::Replace($pendingJar, $targetJar, $backupJar, $true)
        } catch {
            $replaceError = $_
            if ((Test-Path -LiteralPath $pendingJar -PathType Leaf) -and
                (Test-Path -LiteralPath $targetJar -PathType Leaf) -and
                -not (Test-Path -LiteralPath $backupJar)) {
                Move-Item -LiteralPath $targetJar -Destination $backupJar
                try {
                    Move-Item -LiteralPath $pendingJar -Destination $targetJar
                } catch {
                    $moveError = $_
                    if (-not (Test-Path -LiteralPath $targetJar) -and
                        (Test-Path -LiteralPath $backupJar -PathType Leaf)) {
                        Move-Item -LiteralPath $backupJar -Destination $targetJar
                    }
                    throw "Deployment failed and the previous JAR was restored. Replace error: $($replaceError.Exception.Message) Move error: $($moveError.Exception.Message)"
                }
            } else {
                throw $replaceError
            }
        }
    } else {
        Move-Item -LiteralPath $pendingJar -Destination $targetJar
    }

    $deployedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $targetJar).Hash
    if ($deployedHash -ne $sourceHash) {
        $failedJar = Join-Path $backupDir "$targetName.failed-deploy-$stamp.jar"
        Move-Item -LiteralPath $targetJar -Destination $failedJar
        if (Test-Path -LiteralPath $backupJar -PathType Leaf) {
            Move-Item -LiteralPath $backupJar -Destination $targetJar
        }
        throw "Deployed JAR hash mismatch. The previous JAR was restored; failed JAR: $failedJar"
    }

    if ($null -ne $oldHash) {
        if (-not (Test-Path -LiteralPath $backupJar -PathType Leaf)) {
            throw "Deployment succeeded, but the backup is missing: $backupJar"
        }
        $backupHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $backupJar).Hash
        if ($backupHash -ne $oldHash) {
            throw "Deployment succeeded, but the backup hash does not match the previous JAR: $backupJar"
        }
        Write-Host "Backup: $backupJar"
    }

    $activeAfter = @(Get-ChildItem -LiteralPath $resolvedModsDir -File |
        Where-Object { $_.Name -like 'blocky_music*.jar' })
    if ($activeAfter.Count -ne 1 -or $activeAfter[0].Name -ne $targetName) {
        throw 'Deployment postcondition failed: exactly one main Blocky Music JAR must be enabled.'
    }

    Write-Host "Deployed: $targetJar"
    Write-Host "SHA-256: $deployedHash"
    Write-Host 'Restart Minecraft before runtime testing.'
}

$gradleArguments = switch ($Mode) {
    'Compile'    { @('compileJava', '--build-cache', '--configuration-cache', '--console=plain') }
    'Check'      { @('classes', '--build-cache', '--configuration-cache', '--console=plain') }
    'Build'      { @('assemble', '--build-cache', '--configuration-cache', '--console=plain') }
    'Deploy'     { @('assemble', '--build-cache', '--configuration-cache', '--console=plain') }
    'Full'       { @('clean', 'build', '--build-cache', '--configuration-cache', '--stacktrace', '--console=plain') }
    'FullDeploy' { @('clean', 'build', '--build-cache', '--configuration-cache', '--stacktrace', '--console=plain') }
}

if ($Mode -in @('Deploy', 'FullDeploy') -and [string]::IsNullOrWhiteSpace($ModsDir)) {
    throw "$Mode needs the mods folder of your test instance: pass -ModsDir <folder> or set BLOCKY_MODS_DIR."
}

$timer = [System.Diagnostics.Stopwatch]::StartNew()
Invoke-Gradle -Arguments $gradleArguments
if ($Mode -in @('Deploy', 'FullDeploy')) {
    Install-BuiltJar
}
$timer.Stop()
Write-Host ("Completed {0} in {1:N2}s" -f $Mode, $timer.Elapsed.TotalSeconds)
