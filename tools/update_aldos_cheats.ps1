param(
    [string]$CodelistUrl = "http://ps3.aldostools.org/codelist.html",
    [string]$RawBaseUrl = "https://raw.githubusercontent.com/aldostools/webMAN-MOD/master/_Projects_/codelists/",
    [string]$OutputDir = "app/src/main/assets/cheats"
)

$ErrorActionPreference = "Stop"

function Clean-Html([string]$value) {
    if ([string]::IsNullOrWhiteSpace($value)) {
        return ""
    }

    return (($value -replace "<.*?>", "") `
        -replace "&amp;", "&" `
        -replace "&#39;", "'" `
        -replace "&quot;", '"' `
        -replace "&nbsp;", " ").Trim()
}

function Get-TitleIds([string]$value) {
    if ([string]::IsNullOrWhiteSpace($value)) {
        return @()
    }

    return [regex]::Matches($value.ToUpperInvariant(), "\b[A-Z]{4}\d{5}\b") |
        ForEach-Object { $_.Value } |
        Select-Object -Unique
}

function Get-AssetName([int]$index, [string]$fileName) {
    $safe = $fileName -replace "[^A-Za-z0-9._ -]", "_"
    $safe = $safe.Trim()
    if ($safe.Length -gt 96) {
        $safe = $safe.Substring(0, 96).Trim()
    }

    return ("{0:D4}_{1}.ncl" -f $index, $safe)
}

function Get-CheatSummary([string]$text) {
    $normalized = ($text -replace "`r`n", "`n") -replace "`r", "`n"
    $blocks = $normalized -split "`n#"
    $convertible = 0
    $risky = 0

    foreach ($block in $blocks) {
        $lines = $block -split "`n" |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ -and $_ -ne "#" }

        if ($lines.Count -eq 0) {
            continue
        }

        $writes = 0
        $unsupported = $false
        foreach ($line in $lines) {
            if ($line -match "^0\s+[0-9A-Fa-f]{8}\s+[0-9A-Fa-f]{8}(?:\s+.*)?$") {
                $writes++
            } elseif ($line -match "^\[" -or $line -match "^[Bb]\s+" -or $line -match "^[1-9A-Fa-f]\s+") {
                $unsupported = $true
            } elseif ($line -match "^0\s+") {
                $unsupported = $true
            }
        }

        if ($writes -gt 0 -and -not $unsupported) {
            $convertible++
        } elseif ($writes -gt 0 -or $unsupported) {
            $risky++
        }
    }

    return @{
        convertibleCount = $convertible
        riskyCount = $risky
    }
}

function Get-Utf8Text([string]$url) {
    $client = [System.Net.WebClient]::new()
    try {
        $bytes = $client.DownloadData($url)
        return [System.Text.Encoding]::UTF8.GetString($bytes)
    } finally {
        $client.Dispose()
    }
}

function Get-NormalizedName([string]$value) {
    return ($value -replace "\s+", " ").Trim()
}

function Resolve-CodelistName([string]$fileName, [string]$version, [hashtable]$exactNames, [hashtable]$normalizedNames) {
    $candidates = New-Object System.Collections.Generic.List[string]
    $candidates.Add($fileName)

    if (-not [string]::IsNullOrWhiteSpace($version)) {
        $duplicateVersion = " $version $version"
        if ($fileName.EndsWith($duplicateVersion, [System.StringComparison]::Ordinal)) {
            $candidates.Add($fileName.Substring(0, $fileName.Length - $version.Length - 1))
        }
    }

    foreach ($candidate in $candidates) {
        if ($exactNames.ContainsKey($candidate)) {
            return $candidate
        }

        $normalized = Get-NormalizedName $candidate
        if ($normalizedNames.ContainsKey($normalized)) {
            return $normalizedNames[$normalized]
        }
    }

    return $fileName
}

$root = Resolve-Path "."
$outputPath = Join-Path $root $OutputDir
$nclPath = Join-Path $outputPath "ncl"
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
New-Item -ItemType Directory -Force -Path $nclPath | Out-Null

$html = Get-Utf8Text $CodelistUrl
$rows = [regex]::Matches($html, "<tr><td>(.*?)<td>(.*?)<td>(.*?)<td>(.*?)</tr>", "IgnoreCase,Singleline")
$tree = Invoke-RestMethod -Uri "https://api.github.com/repos/aldostools/webMAN-MOD/git/trees/master?recursive=1" -Headers @{ "User-Agent" = "RPCSX-Easy" }
$exactNames = @{}
$normalizedNames = @{}
$tree.tree |
    Where-Object { $_.path -like "_Projects_/codelists/*.ncl" } |
    ForEach-Object {
        $name = [System.IO.Path]::GetFileNameWithoutExtension($_.path)
        $exactNames[$name] = $true
        $normalized = Get-NormalizedName $name
        if (-not $normalizedNames.ContainsKey($normalized)) {
            $normalizedNames[$normalized] = $name
        }
    }

$entries = New-Object System.Collections.Generic.List[object]
$seen = @{}
$failures = New-Object System.Collections.Generic.List[object]
$referencedAssets = [System.Collections.Generic.HashSet[string]]::new()

$index = 0
foreach ($row in $rows) {
    $titleIdCell = Clean-Html $row.Groups[1].Value
    $title = Clean-Html $row.Groups[2].Value
    $version = Clean-Html $row.Groups[3].Value
    $size = Clean-Html $row.Groups[4].Value

    if ([string]::IsNullOrWhiteSpace($title)) {
        continue
    }

    $fileName = $title
    if ($titleIdCell) {
        $fileName += " $titleIdCell"
    }
    if ($version) {
        $fileName += " $version"
    }
    if ($seen.ContainsKey($fileName)) {
        continue
    }
    $seen[$fileName] = $true

    $assetName = Get-AssetName $index $fileName
    [void]$referencedAssets.Add($assetName)
    $assetFile = Join-Path $nclPath $assetName
    $remoteName = Resolve-CodelistName $fileName $version $exactNames $normalizedNames
    $encoded = [System.Uri]::EscapeDataString($remoteName)
    $url = "$RawBaseUrl$encoded.ncl"

    try {
        if (Test-Path $assetFile) {
            $content = [System.IO.File]::ReadAllText($assetFile)
        } else {
            $content = Get-Utf8Text $url
            [System.IO.File]::WriteAllText($assetFile, $content)
        }
        $summary = Get-CheatSummary $content
    } catch {
        $failures.Add([pscustomobject]@{
            fileName = $fileName
            url = $url
            error = $_.Exception.Message
        })
        $summary = @{
            convertibleCount = 0
            riskyCount = 0
        }
    }

    $entries.Add([pscustomobject]@{
        titleIds = @(Get-TitleIds $titleIdCell)
        title = $title
        version = $version
        size = $size
        fileName = $fileName
        sourceName = if ($remoteName -ne $fileName) { $remoteName } else { $null }
        assetName = $assetName
        convertibleCount = $summary.convertibleCount
        riskyCount = $summary.riskyCount
    })

    $index++
    if (($index % 100) -eq 0) {
        Write-Host "Bundled $index cheat files..."
    }
}

Get-ChildItem -Path $nclPath -Filter "*.ncl" -File |
    Where-Object { -not $referencedAssets.Contains($_.Name) } |
    Remove-Item -Force

$indexJson = $entries | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText((Join-Path $outputPath "aldos_index.json"), $indexJson, $utf8NoBom)

$sourceText = @"
Bundled AldosTools / Artemis PS3 cheat database snapshot.

Generated from:
- $CodelistUrl
- ${RawBaseUrl}<file>.ncl

The online codelist states that these codes are mirrored from the community
Artemis code list repository maintained by bucanero.

Generated at: $((Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ"))
Entries: $($entries.Count)
Failed downloads: $($failures.Count)
"@
[System.IO.File]::WriteAllText((Join-Path $outputPath "SOURCE.txt"), $sourceText, $utf8NoBom)

if ($failures.Count -eq 0) {
    [System.IO.File]::WriteAllText((Join-Path $outputPath "download_failures.json"), "[]", $utf8NoBom)
} else {
    $failuresJson = $failures | ConvertTo-Json -Depth 5
    [System.IO.File]::WriteAllText((Join-Path $outputPath "download_failures.json"), $failuresJson, $utf8NoBom)
}

Write-Host "Done. Entries: $($entries.Count). Failed downloads: $($failures.Count)."
