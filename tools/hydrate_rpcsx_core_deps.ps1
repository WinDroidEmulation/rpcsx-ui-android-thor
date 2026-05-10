param(
    [int]$Jobs = 8
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")

git -C $repoRoot config core.longpaths true
git -C $repoRoot submodule sync --recursive
git -C $repoRoot -c core.longpaths=true -c submodule.fetchJobs=$Jobs submodule update `
    --init `
    --recursive `
    --filter=blob:none `
    --jobs $Jobs `
    -- app/src/main/cpp/rpcsx

Write-Host "RPCSX core dependency submodules are hydrated."
