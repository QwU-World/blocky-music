$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$projectDir = Split-Path -Parent $PSScriptRoot
$texturePath = Join-Path $projectDir 'src/main/resources/assets/music_battle/textures/item/test_rap_god_master_spawn_egg.png'
New-Item -ItemType Directory -Path (Split-Path -Parent $texturePath) -Force | Out-Null
$bitmap = [System.Drawing.Bitmap]::new(16, 16)
$rows = @{
    2 = @(6,9); 3 = @(5,10); 4 = @(4,11); 5 = @(3,12); 6 = @(3,12)
    7 = @(3,12); 8 = @(2,13); 9 = @(2,13); 10 = @(2,13); 11 = @(2,13)
    12 = @(3,12); 13 = @(4,11); 14 = @(6,9)
}
function Set-EggPixel([int]$x, [int]$y, [string]$hex) {
    $bitmap.SetPixel($x, $y, [System.Drawing.ColorTranslator]::FromHtml($hex))
}
try {
    foreach ($row in $rows.Keys) {
        for ($x = $rows[$row][0]; $x -le $rows[$row][1]; $x++) {
            $edge = $x -eq $rows[$row][0] -or $x -eq $rows[$row][1] -or $row -eq 2 -or $row -eq 14
            $color = if ($edge) { '#281B39' } elseif ($x -lt 6 -and $row -lt 9) { '#F08BAF' } elseif ($row -gt 10 -or $x -gt 10) { '#843B70' } else { '#BE508E' }
            Set-EggPixel $x $row $color
        }
    }
    foreach ($xy in @(@(6,3),@(5,4),@(4,5),@(4,6))) { Set-EggPixel $xy[0] $xy[1] '#FFD6DC' }
    foreach ($xy in @(@(3,9),@(4,9),@(4,10),@(11,5),@(11,6),@(12,10),@(11,11))) { Set-EggPixel $xy[0] $xy[1] '#58BCBF' }
    # Compact outlined microphone: metallic grille, gold collar and dark handle.
    foreach ($xy in @(@(7,5),@(8,5),@(9,5),@(6,6),@(10,6),@(6,7),@(10,7),@(6,8),@(10,8),@(7,9),@(9,9),@(7,10),@(9,10),@(7,11),@(9,11),@(8,12))) { Set-EggPixel $xy[0] $xy[1] '#241D30' }
    foreach ($xy in @(@(7,6),@(8,6),@(9,6),@(7,7),@(8,7),@(9,7),@(7,8),@(8,8),@(9,8))) { Set-EggPixel $xy[0] $xy[1] '#9B9CAF' }
    foreach ($xy in @(@(7,6),@(8,6),@(9,7))) { Set-EggPixel $xy[0] $xy[1] '#E3E2E8' }
    Set-EggPixel 8 7 '#55576B'
    Set-EggPixel 8 9 '#F6C15A'
    Set-EggPixel 8 10 '#79637C'
    Set-EggPixel 8 11 '#C6A1AE'
    $bitmap.Save($texturePath, [System.Drawing.Imaging.ImageFormat]::Png)
    Write-Output $texturePath
} finally { $bitmap.Dispose() }
