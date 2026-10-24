$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$langDir = Join-Path $projectDir 'src/main/resources/assets/music_battle/lang'
$ru = Get-Content -LiteralPath (Join-Path $langDir 'ru_ru.json') -Raw | ConvertFrom-Json -AsHashtable
$en = Get-Content -LiteralPath (Join-Path $langDir 'en_us.json') -Raw | ConvertFrom-Json -AsHashtable
$issues = [Collections.Generic.List[string]]::new()
foreach ($key in $ru.Keys) {
    if (!$en.ContainsKey($key)) { $issues.Add("Missing English key: $key"); continue }
    if ($en[$key] -match '[А-Яа-яЁё]') { $issues.Add("Russian in English value: $key") }
    $ruArgs = @([regex]::Matches($ru[$key], '%(?:\d+\$)?[+\-0-9.]*[sdfX%]') | ForEach-Object Value | Sort-Object) -join '|'
    $enArgs = @([regex]::Matches($en[$key], '%(?:\d+\$)?[+\-0-9.]*[sdfX%]') | ForEach-Object Value | Sort-Object) -join '|'
    if ($ruArgs -ne $enArgs) { $issues.Add("Placeholder mismatch: $key ($ruArgs / $enArgs)") }
}
foreach ($key in $en.Keys) { if (!$ru.ContainsKey($key)) { $issues.Add("Missing Russian key: $key") } }
$literalCount = 0
$references = [Collections.Generic.HashSet[string]]::new()
$tokens = '(?s)//[^\r\n]*|/\*.*?\*/|(?<literal>"(?:\\.|[^"\\])*")|''(?:\\.|[^''\\])*'''
Get-ChildItem -LiteralPath (Join-Path $projectDir 'src/main/java') -Filter '*.java' -Recurse | ForEach-Object {
    $file = $_
    $source = Get-Content -LiteralPath $file.FullName -Raw
    foreach ($match in [regex]::Matches($source, $tokens)) {
        if (!$match.Groups['literal'].Success) { continue }
        $literalCount++
        $value = $match.Value.Substring(1, $match.Value.Length - 2)
        # Decode Unicode escapes as well as literal Cyrillic; do not treat comments as UI.
        $value = [regex]::Replace($value, '\\u([0-9a-fA-F]{4})', {param($m) [char][Convert]::ToInt32($m.Groups[1].Value,16)})
        if ($value -match '[А-Яа-яЁё]') { $issues.Add("Unlocalized Java literal: $($file.Name): $value") }
        if ($value -match '^music_battle\.l10n\.[a-zA-Z0-9_.]+$' -and !$value.EndsWith('.')) {
            [void]$references.Add($value)
            if (!$en.ContainsKey($value)) { $issues.Add("Missing source translation: $($file.Name): $value") }
        }
    }
}
if ($issues.Count) { $issues | ForEach-Object { Write-Host $_ }; throw "Localization audit: $($issues.Count) issue(s)" }
"Localization audit PASS: $($en.Count) RU/EN keys, $($references.Count) referenced extracted keys, $literalCount Java literals; no untranslated Cyrillic or mismatched placeholders."
