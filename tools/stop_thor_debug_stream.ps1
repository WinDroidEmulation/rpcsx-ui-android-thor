param(
    [string]$Session = "",
    [string]$Package = "net.rpcsx.easy",
    [switch]$Latest
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\thor_debug_common.ps1"

$RepoRoot = Get-ThorRepoRoot
$Adb = Resolve-ThorAdb
$SessionDir = Resolve-ThorStreamSession -RepoRoot $RepoRoot -Session $Session -Latest:$Latest
$processFile = Join-Path $SessionDir "stream-processes.json"

if (Test-Path $processFile) {
    $processes = Get-Content -LiteralPath $processFile -Raw | ConvertFrom-Json
    foreach ($process in @($processes)) {
        try {
            Stop-Process -Id $process.pid -Force -ErrorAction Stop
            "stopped $($process.name) pid=$($process.pid)" | Out-File -LiteralPath (Join-Path $SessionDir "stop-log.txt") -Append -Encoding UTF8
        } catch {
            "already stopped $($process.name) pid=$($process.pid): $($_.Exception.Message)" | Out-File -LiteralPath (Join-Path $SessionDir "stop-log.txt") -Append -Encoding UTF8
        }
    }
} else {
    "No stream-processes.json found." | Out-File -LiteralPath (Join-Path $SessionDir "stop-log.txt") -Append -Encoding UTF8
}

$FinalDir = Join-Path $SessionDir "final"
$DeviceFilesDir = Join-Path $FinalDir "device-files"
New-Item -ItemType Directory -Force -Path $FinalDir | Out-Null
New-Item -ItemType Directory -Force -Path $DeviceFilesDir | Out-Null

Write-ThorStandardSnapshot $Adb $FinalDir $Package "final"
Invoke-ThorAdbText $Adb $FinalDir "final-logcat-tail.txt" @("logcat", "-d", "-v", "threadtime", "-t", "10000") -AllowFailure | Out-Null

$remoteRoot = "/storage/emulated/0/Android/data/$Package/files"
$pullTargets = @(
    @{ Remote = "$remoteRoot/cache/RPCSX.log"; Local = "cache/RPCSX.log" },
    @{ Remote = "$remoteRoot/cache/RPCSX.old.log"; Local = "cache/RPCSX.old.log" },
    @{ Remote = "$remoteRoot/cache/TTY.log"; Local = "cache/TTY.log" },
    @{ Remote = "$remoteRoot/config/config.yml"; Local = "config/config.yml" },
    @{ Remote = "$remoteRoot/config/games.yml"; Local = "config/games.yml" },
    @{ Remote = "$remoteRoot/config/patch_config.yml"; Local = "config/patch_config.yml" }
)

foreach ($item in $pullTargets) {
    Copy-ThorAdbFile $Adb $FinalDir $DeviceFilesDir $item.Remote $item.Local | Out-Null
}

@(
    "# Thor Debug Stream Stopped",
    "",
    "- Stopped: $(Get-Date -Format o)",
    "- Session: $SessionDir",
    "",
    'Final files are under `final/`. Keep `logcat-live.txt` and `rpcsx-live-tail.txt` with the final pull when comparing live symptoms against final emulator state.'
) | Set-Content -LiteralPath (Join-Path $SessionDir "STOPPED.md") -Encoding UTF8

Write-Host "Thor debug stream stopped:"
Write-Host $SessionDir
