param(
    [string]$Session = "",
    [string]$Package = "net.rpcsx.easy",
    [switch]$Latest,
    [switch]$NoRefresh,
    [int]$TailLines = 80
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\thor_debug_common.ps1"

function Get-TailText {
    param(
        [string]$Path,
        [int]$Lines
    )

    if (-not (Test-Path $Path)) {
        return @()
    }

    return @(Get-Content -LiteralPath $Path -Tail $Lines)
}

function Get-InterestingTail {
    param(
        [string]$Path,
        [int]$Lines
    )

    if (-not (Test-Path $Path)) {
        return @()
    }

    $patterns = Get-ThorInterestingPatterns
    return @(Get-Content -LiteralPath $Path -Tail 5000 | Select-String -Pattern $patterns | Select-Object -Last $Lines | ForEach-Object { $_.Line })
}

function Test-AnyPattern {
    param(
        [string[]]$Lines,
        [string[]]$Patterns
    )

    $text = ($Lines -join "`n")
    foreach ($pattern in $Patterns) {
        if ($text -match $pattern) {
            return $true
        }
    }

    return $false
}

function Select-RelevantLogcatLines {
    param(
        [string[]]$Lines,
        [string]$Package,
        [string[]]$Pids
    )

    $relevant = New-Object System.Collections.Generic.List[string]
    foreach ($line in $Lines) {
        if ($line -match [regex]::Escape($Package) -or $line -match "RPCS3|RPCSX") {
            $relevant.Add($line)
            continue
        }

        foreach ($pid in $Pids) {
            if ($line -match "\s+$([regex]::Escape($pid))\s+") {
                $relevant.Add($line)
                break
            }
        }
    }

    return @($relevant)
}

$RepoRoot = Get-ThorRepoRoot
$Adb = Resolve-ThorAdb
$SessionDir = Resolve-ThorStreamSession -RepoRoot $RepoRoot -Session $Session -Latest:$Latest
$SummaryDir = Join-Path $SessionDir "live-summary"
New-Item -ItemType Directory -Force -Path $SummaryDir | Out-Null

if (-not $NoRefresh) {
    Write-ThorStandardSnapshot $Adb $SummaryDir $Package "now"
    Invoke-ThorAdbText $Adb $SummaryDir "now-logcat-tail.txt" @("logcat", "-d", "-v", "threadtime", "-t", "3000") -AllowFailure | Out-Null
}

$logcatPath = Join-Path $SessionDir "logcat-live.txt"
$rpcsxTailPath = Join-Path $SessionDir "rpcsx-live-tail.txt"
$processFile = Join-Path $SessionDir "stream-processes.json"
$pidPath = Join-Path $SummaryDir "now-pid.txt"
$activityPath = Join-Path $SummaryDir "now-activity.txt"
$topPath = Join-Path $SummaryDir "now-top-threads.txt"

$logcatInteresting = Get-InterestingTail $logcatPath $TailLines
$rpcsxInteresting = Get-InterestingTail $rpcsxTailPath $TailLines
$logcatTail = Get-TailText $logcatPath 20
$rpcsxTail = Get-TailText $rpcsxTailPath 30
$allInteresting = @($logcatInteresting + $rpcsxInteresting)

$pidLines = Get-TailText $pidPath 20
$activityLines = Get-TailText $activityPath 80
$topLines = Get-TailText $topPath 40
$currentPids = @($pidLines | Where-Object { $_ -match '^\d+$' })
$relevantLogcatInteresting = Select-RelevantLogcatLines $logcatInteresting $Package $currentPids

$processStatus = @()
if (Test-Path $processFile) {
    $processes = Get-Content -LiteralPath $processFile -Raw | ConvertFrom-Json
    foreach ($process in @($processes)) {
        $alive = $false
        try {
            $null = Get-Process -Id $process.pid -ErrorAction Stop
            $alive = $true
        } catch {
            $alive = $false
        }
        $processStatus += "$($process.name): pid=$($process.pid) alive=$alive"
    }
}

$diagnosisLines = @($rpcsxInteresting + $relevantLogcatInteresting)
$diagnosis = @()
if (Test-AnyPattern $diagnosisLines @("FATAL EXCEPTION", "Fatal signal", "SIGSEGV", "SIGABRT", "tombstone", "Abort message")) {
    $diagnosis += "Crash signature present. Preserve this session, stop the stream, and inspect final/logcat plus tombstone lines first."
}
if (Test-AnyPattern $diagnosisLines @("semaphore_acquire has timed out")) {
    $diagnosis += "RSX semaphore timeout seen. Treat as GPU/RSX sync stall candidate before blaming UI."
}
if (Test-AnyPattern $diagnosisLines @("cellGameDataCheck.*not found", "directory .* not found")) {
    $diagnosis += "Game-data directory checks are failing. Check dev_hdd0/game install state and whether the game is waiting on install/content flow."
}
if (Test-AnyPattern $diagnosisLines @("SPU: Building function", "PPU: LLVM")) {
    $diagnosis += "Compiler activity is visible. If screen is black but process is alive, distinguish warmup/compile from a real hang."
}
if (Test-AnyPattern $diagnosisLines @("Failed to lock sudo memory", "mlock")) {
    $diagnosis += "Android memlock warning present. Usually not the immediate crash cause, but keep it in perf notes."
}
if (Test-AnyPattern $diagnosisLines @("OutOfMemory", "Cannot allocate", "lowmemorykiller")) {
    $diagnosis += "Memory pressure signal present. Compare meminfo and cache size before widening features."
}
if ($diagnosis.Count -eq 0) {
    $diagnosis += "No obvious crash/stall signature in the latest tail. Check activity focus, PID, and whether RPCSX.log is still advancing."
}

$summary = New-Object System.Collections.Generic.List[string]
$summary.Add("# Thor Live Debug Summary")
$summary.Add("")
$summary.Add("- Session: $SessionDir")
$summary.Add("- Refreshed: $(Get-Date -Format o)")
$summary.Add("- Logcat file: $logcatPath")
$summary.Add("- RPCSX live tail file: $rpcsxTailPath")
$summary.Add("")
$summary.Add("## Stream Processes")
if ($processStatus.Count -gt 0) {
    foreach ($line in $processStatus) {
        $summary.Add("- $line")
    }
} else {
    $summary.Add("- No process file found.")
}
$summary.Add("")
$summary.Add("## Current App State")
foreach ($line in $pidLines) {
    if ($line -and $line -notmatch '^#') {
        $summary.Add($line)
    }
}
foreach ($line in $activityLines | Select-String -Pattern "topResumedActivity|ResumedActivity|mCurrentFocus|mFocusedApp|RPCSXActivity|MainActivity" | Select-Object -First 12) {
    $summary.Add($line.Line)
}
$summary.Add("")
$summary.Add("## Quick Read")
foreach ($line in $diagnosis) {
    $summary.Add("- $line")
}
$summary.Add("")
$summary.Add("## Latest Interesting RPCSX Lines")
if ($rpcsxInteresting.Count -gt 0) {
    foreach ($line in ($rpcsxInteresting | Select-Object -Last $TailLines)) {
        $summary.Add($line)
    }
} else {
    $summary.Add("(none)")
}
$summary.Add("")
$summary.Add("## Latest Interesting Logcat Lines")
if ($relevantLogcatInteresting.Count -gt 0) {
    foreach ($line in ($relevantLogcatInteresting | Select-Object -Last $TailLines)) {
        $summary.Add($line)
    }
} else {
    $summary.Add("(none relevant to current RPCSX package/PID)")
}
$summary.Add("")
$summary.Add("## Last Raw RPCSX Tail")
foreach ($line in $rpcsxTail) {
    $summary.Add($line)
}
$summary.Add("")
$summary.Add("## Hot Threads")
foreach ($line in $topLines | Select-String -Pattern "$Package|rpcsx|RPCS3|PPU|SPU|RSX|CPU" | Select-Object -First 30) {
    $summary.Add($line.Line)
}

$summaryPath = Join-Path $SessionDir "summary-latest.md"
$summary | Set-Content -LiteralPath $summaryPath -Encoding UTF8
$summary | Write-Output
