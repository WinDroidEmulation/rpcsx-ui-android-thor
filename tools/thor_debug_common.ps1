$Script:ThorDefaultPackage = "net.rpcsx.easy"

function Resolve-ThorAdb {
    $candidates = @()

    if ($env:ANDROID_HOME) {
        $candidates += Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
    }

    $candidates += "C:\Users\leanerdesigner\AppData\Local\Android\Sdk\platform-tools\adb.exe"

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    $fromPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($fromPath) {
        return $fromPath.Source
    }

    throw "adb not found. Set ANDROID_HOME or install Android platform-tools."
}

function Get-ThorRepoRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

function New-ThorSafeLabel {
    param([string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return "capture"
    }

    $safe = $Value -replace '[^A-Za-z0-9._-]+', '-'
    $safe = $safe.Trim('-')
    if ([string]::IsNullOrWhiteSpace($safe)) {
        return "capture"
    }

    return $safe
}

function Join-ThorNativeArguments {
    param([string[]]$NativeArgs)

    $quoted = foreach ($arg in $NativeArgs) {
        if ($null -eq $arg) {
            '""'
        } elseif ($arg -notmatch '[\s"]') {
            $arg
        } else {
            '"' + ($arg -replace '"', '\"') + '"'
        }
    }

    return ($quoted -join " ")
}

function Invoke-ThorAdbCapture {
    param(
        [string]$Adb,
        [string[]]$AdbArgs,
        [string]$StdoutPath,
        [string]$StderrPath,
        [int]$TimeoutSeconds = 0
    )

    $encoding = New-Object System.Text.UTF8Encoding $false
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Adb
    $psi.Arguments = Join-ThorNativeArguments $AdbArgs
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $psi
    [void]$process.Start()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()

    if ($TimeoutSeconds -gt 0) {
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            try {
                $process.Kill()
            } catch {
            }
            [System.IO.File]::WriteAllText($StdoutPath, $stdoutTask.Result, $encoding)
            [System.IO.File]::WriteAllText($StderrPath, "Timed out after $TimeoutSeconds seconds.", $encoding)
            return 124
        }
    } else {
        $process.WaitForExit()
    }

    [System.IO.File]::WriteAllText($StdoutPath, $stdoutTask.Result, $encoding)
    [System.IO.File]::WriteAllText($StderrPath, $stderrTask.Result, $encoding)

    return $process.ExitCode
}

function Invoke-ThorAdbText {
    param(
        [string]$Adb,
        [string]$CaptureDir,
        [string]$Name,
        [string[]]$AdbArgs,
        [switch]$AllowFailure,
        [int]$TimeoutSeconds = 0
    )

    New-Item -ItemType Directory -Force -Path $CaptureDir | Out-Null
    $path = Join-Path $CaptureDir $Name
    $header = @(
        "# adb $($AdbArgs -join ' ')",
        "# captured $(Get-Date -Format o)",
        ""
    )
    Set-Content -LiteralPath $path -Value $header -Encoding UTF8

    $tempName = New-ThorSafeLabel $Name
    $stdoutPath = Join-Path $CaptureDir "$tempName.stdout.tmp"
    $stderrPath = Join-Path $CaptureDir "$tempName.stderr.tmp"
    $exitCode = Invoke-ThorAdbCapture -Adb $Adb -AdbArgs $AdbArgs -StdoutPath $stdoutPath -StderrPath $stderrPath -TimeoutSeconds $TimeoutSeconds

    if (Test-Path $stdoutPath) {
        Get-Content -LiteralPath $stdoutPath | Out-File -LiteralPath $path -Append -Encoding UTF8
        Remove-Item -LiteralPath $stdoutPath -Force
    }

    if (Test-Path $stderrPath) {
        $stderrContent = Get-Content -LiteralPath $stderrPath
        if ($stderrContent) {
            "# stderr" | Out-File -LiteralPath $path -Append -Encoding UTF8
            $stderrContent | Out-File -LiteralPath $path -Append -Encoding UTF8
        }
        Remove-Item -LiteralPath $stderrPath -Force
    }

    if ($exitCode -ne 0) {
        "exit=$exitCode" | Out-File -LiteralPath $path -Append -Encoding UTF8
        if (-not $AllowFailure) {
            throw "adb $($AdbArgs -join ' ') failed with exit code $exitCode"
        }
    }

    return $path
}

function Copy-ThorAdbFile {
    param(
        [string]$Adb,
        [string]$CaptureDir,
        [string]$DeviceFilesDir,
        [string]$Remote,
        [string]$LocalName
    )

    $target = Join-Path $DeviceFilesDir $LocalName
    $targetParent = Split-Path -Parent $target
    New-Item -ItemType Directory -Force -Path $targetParent | Out-Null

    $pullLog = Join-Path $CaptureDir "adb-pull.log"
    "adb pull $Remote $target" | Out-File -LiteralPath $pullLog -Append -Encoding UTF8

    $tempName = New-ThorSafeLabel $LocalName
    $stdoutPath = Join-Path $CaptureDir "$tempName.stdout.tmp"
    $stderrPath = Join-Path $CaptureDir "$tempName.stderr.tmp"
    $exitCode = Invoke-ThorAdbCapture -Adb $Adb -AdbArgs @("pull", $Remote, $target) -StdoutPath $stdoutPath -StderrPath $stderrPath

    if (Test-Path $stdoutPath) {
        Get-Content -LiteralPath $stdoutPath | Out-File -LiteralPath $pullLog -Append -Encoding UTF8
        Remove-Item -LiteralPath $stdoutPath -Force
    }

    if (Test-Path $stderrPath) {
        Get-Content -LiteralPath $stderrPath | Out-File -LiteralPath $pullLog -Append -Encoding UTF8
        Remove-Item -LiteralPath $stderrPath -Force
    }

    "exit=$exitCode" | Out-File -LiteralPath $pullLog -Append -Encoding UTF8
    "" | Out-File -LiteralPath $pullLog -Append -Encoding UTF8

    return $exitCode
}

function Start-ThorAdbStream {
    param(
        [string]$Adb,
        [string]$CaptureDir,
        [string]$Name,
        [string[]]$AdbArgs
    )

    New-Item -ItemType Directory -Force -Path $CaptureDir | Out-Null
    $stdoutPath = Join-Path $CaptureDir "$Name.txt"
    $stderrPath = Join-Path $CaptureDir "$Name.stderr.txt"
    if (Test-Path $stdoutPath) {
        Remove-Item -LiteralPath $stdoutPath -Force
    }
    if (Test-Path $stderrPath) {
        Remove-Item -LiteralPath $stderrPath -Force
    }

    $argString = Join-ThorNativeArguments $AdbArgs
    $process = Start-Process -FilePath $Adb -ArgumentList $argString -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -WindowStyle Hidden -PassThru

    return [pscustomobject]@{
        name = $Name
        pid = $process.Id
        stdout = $stdoutPath
        stderr = $stderrPath
        command = "adb $($AdbArgs -join ' ')"
    }
}

function Write-ThorLatestStream {
    param(
        [string]$RepoRoot,
        [string]$SessionDir
    )

    $outRoot = Join-Path $RepoRoot "debug-captures"
    New-Item -ItemType Directory -Force -Path $outRoot | Out-Null
    Set-Content -LiteralPath (Join-Path $outRoot "latest-stream.txt") -Value $SessionDir -Encoding UTF8
}

function Resolve-ThorStreamSession {
    param(
        [string]$RepoRoot,
        [string]$Session,
        [switch]$Latest
    )

    if (-not [string]::IsNullOrWhiteSpace($Session)) {
        return (Resolve-Path $Session).Path
    }

    $latestPath = Join-Path (Join-Path $RepoRoot "debug-captures") "latest-stream.txt"
    if ($Latest -and (Test-Path $latestPath)) {
        $path = (Get-Content -LiteralPath $latestPath -Raw).Trim()
        if ($path -and (Test-Path $path)) {
            return (Resolve-Path $path).Path
        }
    }

    throw "No stream session supplied and no latest stream pointer exists."
}

function Get-ThorInterestingPatterns {
    return @(
        "FATAL EXCEPTION",
        "Fatal signal",
        "signal ",
        "Abort message",
        "backtrace",
        "tombstone",
        "ANR",
        "Watchdog",
        "SIGSEGV",
        "SIGABRT",
        "SIGILL",
        "Illegal instruction",
        "OutOfMemory",
        "UnsatisfiedLinkError",
        "No implementation found",
        "RPCS3",
        "RPCSX",
        "Vulkan",
        "Turnip",
        "Adreno",
        "kgsl",
        "cubeb",
        "mlock",
        "Failed to lock",
        "semaphore_acquire has timed out",
        "cellGameDataCheck",
        "directory .* not found",
        "SPU: Building function",
        "PPU: LLVM"
    )
}

function Write-ThorStandardSnapshot {
    param(
        [string]$Adb,
        [string]$CaptureDir,
        [string]$Package = $Script:ThorDefaultPackage,
        [string]$Prefix = ""
    )

    $namePrefix = ""
    if (-not [string]::IsNullOrWhiteSpace($Prefix)) {
        $namePrefix = "$Prefix-"
    }

    $remoteRoot = "/storage/emulated/0/Android/data/$Package/files"
    Invoke-ThorAdbText $Adb $CaptureDir "${namePrefix}pid.txt" @("shell", "pidof $Package") -AllowFailure | Out-Null
    Invoke-ThorAdbText $Adb $CaptureDir "${namePrefix}activity.txt" @("shell", "dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity|mCurrentFocus|mFocusedApp|$Package'") -AllowFailure | Out-Null
    Invoke-ThorAdbText $Adb $CaptureDir "${namePrefix}window.txt" @("shell", "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp|Window\\{.*$Package'") -AllowFailure | Out-Null
    Invoke-ThorAdbText $Adb $CaptureDir "${namePrefix}meminfo.txt" @("shell", "dumpsys meminfo $Package") -AllowFailure | Out-Null
    Invoke-ThorAdbText $Adb $CaptureDir "${namePrefix}thermal.txt" @("shell", "dumpsys thermalservice") -AllowFailure | Out-Null
    Invoke-ThorAdbText $Adb $CaptureDir "${namePrefix}top-threads.txt" @("shell", "top -H -b -n 1 | grep -E '$Package|rpcsx|RPCS3|PPU|SPU|RSX|CPU'") -AllowFailure | Out-Null
    Invoke-ThorAdbText $Adb $CaptureDir "${namePrefix}game-install-summary.txt" @("shell", "find $remoteRoot/config/dev_hdd0/game -maxdepth 3 -type d 2>/dev/null | sort") -AllowFailure | Out-Null
    Invoke-ThorAdbText $Adb $CaptureDir "${namePrefix}cache-summary.txt" @("shell", "du -k -d 3 $remoteRoot/cache 2>/dev/null | sort -n | tail -200") -AllowFailure | Out-Null
}
