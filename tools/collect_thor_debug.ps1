param(
    [string]$Label = "",
    [string]$Package = "net.rpcsx.easy",
    [string]$OutRoot = "debug-captures",
    [int]$LogcatLines = 30000,
    [switch]$Prepare,
    [switch]$Launch,
    [switch]$IncludeBugreport
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\thor_debug_common.ps1"

$RepoRoot = Get-ThorRepoRoot
$Adb = Resolve-ThorAdb
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$safeLabel = New-ThorSafeLabel $Label
$CaptureDir = Join-Path $RepoRoot (Join-Path $OutRoot "$timestamp-$safeLabel")
$DeviceFilesDir = Join-Path $CaptureDir "device-files"

New-Item -ItemType Directory -Force -Path $CaptureDir | Out-Null
New-Item -ItemType Directory -Force -Path $DeviceFilesDir | Out-Null

if ($Prepare) {
    Invoke-ThorAdbText $Adb $CaptureDir "prepare-logcat-clear.txt" @("logcat", "-c") | Out-Null

    if ($Launch) {
        Invoke-ThorAdbText $Adb $CaptureDir "prepare-launch.txt" @("shell", "monkey -p $Package 1") -AllowFailure | Out-Null
    }

    @(
        "# Thor Debug Prepare",
        "",
        "Logcat was cleared at $(Get-Date -Format o).",
        "Reproduce the black screen or crash now.",
        "When the issue is visible, run:",
        "",
        '```powershell',
        ".\tools\collect_thor_debug.ps1 -Label $safeLabel",
        '```'
    ) | Set-Content -LiteralPath (Join-Path $CaptureDir "README.md") -Encoding UTF8

    Write-Host "Prepared debug session. Output: $CaptureDir"
    return
}

Invoke-ThorAdbText $Adb $CaptureDir "adb-devices.txt" @("devices", "-l") | Out-Null
Invoke-ThorAdbText $Adb $CaptureDir "package-version.txt" @("shell", "dumpsys package $Package | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime|installerPackageName'") -AllowFailure | Out-Null
Invoke-ThorAdbText $Adb $CaptureDir "settings-summary.txt" @("shell", "settings get global window_animation_scale; settings get global transition_animation_scale; settings get global animator_duration_scale") -AllowFailure | Out-Null
Invoke-ThorAdbText $Adb $CaptureDir "cpuinfo.txt" @("shell", "cat /proc/cpuinfo") -AllowFailure | Out-Null
Invoke-ThorAdbText $Adb $CaptureDir "surfaceflinger-layers.txt" @("shell", "dumpsys SurfaceFlinger --list | grep -i rpcsx") -AllowFailure | Out-Null
Invoke-ThorAdbText $Adb $CaptureDir "app-files.txt" @("shell", "find /storage/emulated/0/Android/data/$Package/files -maxdepth 4 -type f 2>/dev/null | sort | head -500") -AllowFailure | Out-Null
Invoke-ThorAdbText $Adb $CaptureDir "crash-report-files.txt" @("shell", "find /storage/emulated/0/Android/data/$Package/files/config/dev_hdd0/crash_report -maxdepth 2 -type f -ls 2>/dev/null") -AllowFailure | Out-Null
Write-ThorStandardSnapshot $Adb $CaptureDir $Package

Invoke-ThorAdbText $Adb $CaptureDir "logcat-full.txt" @("logcat", "-d", "-v", "threadtime", "-t", "$LogcatLines") -AllowFailure | Out-Null
Invoke-ThorAdbText $Adb $CaptureDir "logcat-crash-filter.txt" @("logcat", "-d", "-v", "time", "-t", "$LogcatLines") -AllowFailure | Out-Null

$filterPath = Join-Path $CaptureDir "logcat-crash-filter.txt"
$filteredOut = Join-Path $CaptureDir "logcat-interesting.txt"
Select-String -Path $filterPath -Pattern (Get-ThorInterestingPatterns) |
    ForEach-Object { $_.Line } |
    Set-Content -LiteralPath $filteredOut -Encoding UTF8

$remoteRoot = "/storage/emulated/0/Android/data/$Package/files"
$pullTargets = @(
    @{ Remote = "$remoteRoot/cache/RPCSX.log"; Local = "cache/RPCSX.log" },
    @{ Remote = "$remoteRoot/cache/RPCSX.old.log"; Local = "cache/RPCSX.old.log" },
    @{ Remote = "$remoteRoot/cache/RPCSX.log.gz"; Local = "cache/RPCSX.log.gz" },
    @{ Remote = "$remoteRoot/cache/TTY.log"; Local = "cache/TTY.log" },
    @{ Remote = "$remoteRoot/config/config.yml"; Local = "config/config.yml" },
    @{ Remote = "$remoteRoot/config/games.yml"; Local = "config/games.yml" },
    @{ Remote = "$remoteRoot/config/patch_config.yml"; Local = "config/patch_config.yml" },
    @{ Remote = "$remoteRoot/config/rpcn.yml"; Local = "config/rpcn.yml" }
)

foreach ($item in $pullTargets) {
    Copy-ThorAdbFile $Adb $CaptureDir $DeviceFilesDir $item.Remote $item.Local | Out-Null
}

Invoke-ThorAdbText $Adb $CaptureDir "rpcsx-log-tail.txt" @("shell", "tail -n 500 $remoteRoot/cache/RPCSX.log") -AllowFailure | Out-Null
Invoke-ThorAdbText $Adb $CaptureDir "rpcsx-log-errors.txt" @("shell", "grep -E '(^| )E |Fatal|FATAL|failed|Failed|SIG|crash|tombstone|LLVM:|Title|Serial|Cat|Version|sys_rsx|cellGame|cellSaveData|cellAudio|Vulkan|VK|RSX|SPU|semaphore_acquire' $remoteRoot/cache/RPCSX.log | tail -400") -AllowFailure | Out-Null

if ($IncludeBugreport) {
    Invoke-ThorAdbText $Adb $CaptureDir "bugreport-command.txt" @("bugreport", (Join-Path $CaptureDir "bugreport.zip")) -AllowFailure | Out-Null
}

$head = ""
try {
    $head = (git -C $RepoRoot rev-parse --short HEAD 2>$null)
} catch {
    $head = "unknown"
}

@(
    "# Thor Debug Capture",
    "",
    "- Captured: $(Get-Date -Format o)",
    "- Label: $safeLabel",
    "- Package: $Package",
    "- Repo: $head",
    '- Device logs: `logcat-full.txt`, `logcat-interesting.txt`, `device-files/cache/RPCSX.log`',
    '- Start here: `rpcsx-log-errors.txt`, then `activity.txt`, then `cache-summary.txt`.',
    "",
    "## Repro Notes",
    "",
    "- Game/title ID:",
    "- Source type: ISO / folder / PKG / other",
    "- What you saw: black screen / crash to launcher / frozen frame / audio only",
    "- Approx time from boot to failure:",
    "- Custom GPU driver:",
    "- Fast Forward 2x enabled:",
    "- Cheats enabled:"
) | Set-Content -LiteralPath (Join-Path $CaptureDir "README.md") -Encoding UTF8

Write-Host "Thor debug capture written to: $CaptureDir"
