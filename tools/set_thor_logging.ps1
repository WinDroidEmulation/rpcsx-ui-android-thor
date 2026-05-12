param(
    [ValidateSet("Quiet", "Normal", "Verbose", "ReducedLoop", "Status")]
    [string]$Mode = "Status"
)

$ErrorActionPreference = "Stop"

$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    $adb = "adb"
}

function Set-DeviceProp {
    param(
        [string]$Name,
        [string]$Value
    )

    & $adb shell setprop $Name $Value | Out-Null
}

function Get-DeviceProp {
    param([string]$Name)

    $value = (& $adb shell getprop $Name).Trim()
    if ([string]::IsNullOrWhiteSpace($value)) {
        $value = "<unset>"
    }

    "{0}={1}" -f $Name, $value
}

switch ($Mode) {
    "Quiet" {
        Set-DeviceProp "debug.rpcsx.thor.logcat" "0"
        Set-DeviceProp "debug.rpcsx.thor.syscall_stats" "0"
        Set-DeviceProp "debug.rpcsx.thor.spu_reduced_loop_detect" "0"
        Set-DeviceProp "log.tag.RPCS3" "S"
        Set-DeviceProp "log.tag.RPCSX-UI" "W"
        break
    }
    "Normal" {
        Set-DeviceProp "debug.rpcsx.thor.logcat" "1"
        Set-DeviceProp "debug.rpcsx.thor.syscall_stats" "0"
        Set-DeviceProp "debug.rpcsx.thor.spu_reduced_loop_detect" "0"
        Set-DeviceProp "log.tag.RPCS3" "I"
        Set-DeviceProp "log.tag.RPCSX-UI" "I"
        break
    }
    "Verbose" {
        Set-DeviceProp "debug.rpcsx.thor.logcat" "1"
        Set-DeviceProp "debug.rpcsx.thor.syscall_stats" "1"
        Set-DeviceProp "debug.rpcsx.thor.spu_reduced_loop_detect" "0"
        Set-DeviceProp "log.tag.RPCS3" "V"
        Set-DeviceProp "log.tag.RPCSX-UI" "V"
        break
    }
    "ReducedLoop" {
        Set-DeviceProp "debug.rpcsx.thor.logcat" "1"
        Set-DeviceProp "debug.rpcsx.thor.syscall_stats" "0"
        Set-DeviceProp "debug.rpcsx.thor.spu_reduced_loop_detect" "1"
        Set-DeviceProp "log.tag.RPCS3" "V"
        Set-DeviceProp "log.tag.RPCSX-UI" "V"
        break
    }
}

Get-DeviceProp "debug.rpcsx.thor.logcat"
Get-DeviceProp "debug.rpcsx.thor.syscall_stats"
Get-DeviceProp "debug.rpcsx.thor.spu_reduced_loop_detect"
Get-DeviceProp "log.tag.RPCS3"
Get-DeviceProp "log.tag.RPCSX-UI"
