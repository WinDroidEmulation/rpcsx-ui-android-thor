param(
    [string]$Module = "libsre",
    [string[]]$Addresses = @("0x00cc948c", "0x00cc945c"),
    [int]$WaitSeconds = 0,
    [switch]$NoSetProp,
    [string]$GhidraHome = "C:\Users\leanerdesigner\Documents\SteamPortableTools\toolchains\ghidra_12.0.4_PUBLIC",
    [string]$JavaHome = "C:\Users\leanerdesigner\Documents\SteamPortableTools\toolchains\jdk-21.0.11+10"
)

$ErrorActionPreference = "Stop"

$Repo = Resolve-Path (Join-Path $PSScriptRoot "..")
$SafeModule = $Module -replace "[^A-Za-z0-9_.-]", ""
if ([string]::IsNullOrWhiteSpace($SafeModule)) {
    throw "Module must contain at least one safe file-name character."
}

$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    $adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
}
if (-not (Test-Path $adb)) {
    $adb = "adb"
}

$AnalyzeHeadless = Join-Path $GhidraHome "support\analyzeHeadless.bat"
if (-not (Test-Path $AnalyzeHeadless)) {
    throw "Missing Ghidra analyzeHeadless: $AnalyzeHeadless"
}

if (-not (Test-Path (Join-Path $JavaHome "bin\java.exe"))) {
    $JavaHome = Get-ChildItem -Path (Join-Path (Split-Path -Parent $GhidraHome) "jdk-21*") -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome "bin\java.exe"))) {
    throw "Missing JDK 21 for Ghidra. Pass -JavaHome or install the SteamPortableTools toolchain."
}

$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outDir = Join-Path $Repo "debug-captures\ghidra-$SafeModule-$stamp"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

if (-not $NoSetProp) {
    & $adb shell setprop debug.rpcsx.thor.dump_prx $SafeModule | Out-Null
    "debug.rpcsx.thor.dump_prx=$SafeModule" | Set-Content -Path (Join-Path $outDir "device-prop.txt")
}

$remoteCache = "/storage/emulated/0/Android/data/net.rpcsx.easy/files/cache/ppu_progs"
$findScript = "find $remoteCache -type f -name prog.prx 2>/dev/null | grep -i '$SafeModule' | tail -n 1"

$deadline = (Get-Date).AddSeconds($WaitSeconds)
$remoteDump = ""
do {
    $remoteDump = ((& $adb shell $findScript) -join "`n").Trim()
    if ($remoteDump) {
        break
    }

    if ($WaitSeconds -le 0 -or (Get-Date) -ge $deadline) {
        break
    }

    Start-Sleep -Seconds 2
} while ($true)

if (-not $remoteDump) {
    $note = @"
No decrypted PRX dump for '$SafeModule' was found under:
$remoteCache

If this APK has the Thor PRX dump hook, leave:
  debug.rpcsx.thor.dump_prx=$SafeModule

Then restart/boot the title until the module loads. Re-run this script after
the log prints 'Thor PRX dump'.
"@
    $note | Set-Content -Path (Join-Path $outDir "missing-dump.txt")
    Write-Host $note
    exit 2
}

$localPrx = Join-Path $outDir "$SafeModule-prog.prx"
& $adb pull $remoteDump $localPrx | Tee-Object -FilePath (Join-Path $outDir "adb-pull.txt")

$scriptPath = Join-Path $PSScriptRoot "ghidra_scripts"
$projectDir = Join-Path $outDir "projects"
$projectName = "Thor_$SafeModule"
$decompileOut = Join-Path $outDir "$SafeModule-decompile.txt"

$ghidraArgs = @(
    $projectDir,
    $projectName,
    "-import", $localPrx,
    "-overwrite",
    "-scriptPath", $scriptPath,
    "-postScript", "DecompileAddresses.java", $decompileOut
) + $Addresses

& $AnalyzeHeadless @ghidraArgs | Tee-Object -FilePath (Join-Path $outDir "ghidra-headless.txt")
if ($LASTEXITCODE -ne 0) {
    throw "Ghidra headless failed with exit code $LASTEXITCODE"
}

$summary = @"
# Thor Ghidra PRX Probe $stamp

- Module target: $SafeModule
- Remote dump: $remoteDump
- Local PRX: $localPrx
- Addresses: $($Addresses -join ', ')
- Decompile output: $decompileOut
"@
$summary | Set-Content -Path (Join-Path $outDir "summary.md")

Write-Host "Pulled: $localPrx"
Write-Host "Wrote:  $decompileOut"
