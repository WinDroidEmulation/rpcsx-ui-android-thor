---
date: 2026-05-10
semantic_name: thor-feature-doctor-arm64-spu-research
target: AYN Thor Base/Pro/Max, Snapdragon 8 Gen 2, Arm64 RPCSX/RPCS3 core
status: research hypothesis and experiment plan
---

# Thor Feature Doctor: Arm64 SPU Research

## Short Answer

The most promising novel performance idea is not just a better scheduler. It is a **Thor Feature Doctor**: a runtime/build-time system that verifies the exact Arm64 CPU features RPCSX/RPCS3 gives LLVM, then proves whether SPU-heavy code paths are getting the right AArch64 dot-product/int8 codegen on Snapdragon 8 Gen 2.

Why this matters: our current Thor work mostly changes where work runs. This idea asks whether the core is generating the best possible Arm64 work in the first place.

Local source hints make this worth investigating:

- The bundled core recognizes Thor's CPU parts, but several Snapdragon 8 Gen 2 core feature strings are blank.
- The SPU LLVM recompiler has obvious x86 fast paths for `GB`, `GBH`, `GBB`, and `SUMB`, including GFNI, AVX-512, and VNNI paths.
- A local search did not find obvious AArch64 `sdot`/`udot` strings in the SPU LLVM code.
- Arm's public Cortex-A comparison material shows modern Cortex-A cores include newer vector/dot-product-class capabilities. We should confirm exactly what Thor exposes through Android/HWCAP and what LLVM receives.

This could be more interesting than another "performance preset" because it targets the translation quality of SPU workloads, not just CPU placement.

## Sources Checked

- Qualcomm Snapdragon 8 Gen 2 product page: <https://www.qualcomm.com/smartphones/products/8-series/snapdragon-8-gen-2-mobile-platform>
- Arm Cortex-A715 product page: <https://www.arm.com/products/silicon-ip-cpu/cortex-a/cortex-a715>
- Arm Cortex-A processor comparison table PDF: <https://developer.arm.com/-/media/Arm%20Developer%20Community/PDF/Cortex-A%20R%20M%20datasheets/Arm%20Cortex-A%20Comparison%20Table_v4.pdf>
- Android Open Source Project Performance Hint API docs: <https://source.android.com/docs/core/perf/performance-hint-api>
- Android NDK `APerformanceHint` docs: <https://developer.android.com/ndk/reference/group/a-performance-hint>
- Android Thermal API docs: <https://developer.android.com/games/optimize/adpf/thermal>
- RPCS3 FAQ PPU cache note: <https://wiki.rpcs3.net/index.php?title=Help%3AFrequently_Asked_Questions>
- RPCS3 releases page for upstream ARM/SPU activity tracking: <https://github.com/RPCS3/rpcs3/releases>
- Local bundled core: `app/src/main/cpp/rpcsx/rpcs3/Emu/CPU/Backends/AArch64/AArch64Common.cpp`
- Local bundled core: `app/src/main/cpp/rpcsx/rpcs3/Emu/Cell/SPULLVMRecompiler.cpp`
- Local bundled core: `app/src/main/cpp/rpcsx/rpcs3/Emu/Cell/SPUOpcodes.h`
- Local Android fork: `app/src/main/java/net/rpcsx/performance/ThorPerformanceProfile.kt`

## Local Findings

### 1. Thor CPU Parts Are Recognized, But Features Look Underdescribed

The measured Thor CPU topology from earlier work:

| CPU | Core | Part | Role |
| ---: | --- | --- | --- |
| 0-2 | Cortex-A510 | `0xd46` | Efficiency cores |
| 3-4 | Cortex-A715 | `0xd4d` | Big cores |
| 5-6 | Cortex-A710 | `0xd47` | Big cores |
| 7 | Cortex-X3 | `0xd4e` | Prime core |

The local AArch64 backend table currently includes:

| Local source row | CPU | Feature string |
| --- | --- | --- |
| `AArch64Common.cpp:88` | `cortex-a510` | blank |
| `AArch64Common.cpp:115` | `Cortex-A710` | `armv9-a+fp16+bf16+i8mm` |
| `AArch64Common.cpp:117` | `Cortex-A715` | blank |
| `AArch64Common.cpp:124` | `Cortex-X3` | blank |

That does not prove the emulator is missing features. LLVM or runtime probing may still add features elsewhere. But it is suspicious enough to justify a targeted audit, because our target device is exactly this mixed A510/A715/A710/X3 layout.

### 2. SPU LLVM Has Obvious x86 Pattern Fast Paths

In `SPULLVMRecompiler.cpp`:

- `GB`, `GBH`, and `GBB` have `m_use_gfni` paths.
- `SUMB` has `m_use_avx512` and `m_use_vnni` paths.
- Local search found no obvious `SDOT`, `UDOT`, `sdot`, or `udot` strings in the SPU LLVM recompiler.

The relevant local functions:

| Function | Local line | Current obvious fast paths |
| --- | ---: | --- |
| `GB` | `4691` | GFNI |
| `GBH` | `4710` | GFNI |
| `GBB` | `4725` | GFNI |
| `SUMB` | `5242` | AVX-512, VNNI |

This suggests two possible realities:

1. Upstream RPCS3 has newer Arm64 SPU optimizations that our bundled core does not yet include.
2. Arm64 optimization is intended to happen indirectly through LLVM pattern matching, but our feature strings/settings may prevent it.

Both are actionable.

### 3. Scheduler Work Still Matters, But It Has A Ceiling

Our current Thor preset does:

- `Max LLVM Compile Threads = 4`
- `LLVM Precompilation = true`
- `SPU Cache = true`
- blank/generic `Use LLVM CPU`
- runtime process/thread mask `0xF8`

That helps keep work off A510 cores. But if the generated SPU/PPU code is leaving Arm64 features unused, scheduler tuning can only do so much. The scheduler can feed the CPU better; Feature Doctor tries to make each CPU cycle do better work.

## Research Interpretation

### What Is Solid

Solid findings:

- Thor Base/Pro/Max use Snapdragon 8 Gen 2 / Adreno 740 as the shared target class.
- Snapdragon 8 Gen 2 is heterogeneous; Thor has A510, A715, A710, and X3 cores.
- Android explicitly recommends Performance Hint APIs for dynamic CPU behavior and warns against assuming affinity is portable.
- RPCS3 has an official concept of prebuilding PPU caches through `File -> All Titles -> Create PPU Cache`.
- Our local core table leaves several Thor-relevant CPU feature strings blank.
- Our local SPU LLVM code shows explicit x86 fast paths for SPU bit/count/sum patterns but no obvious named Arm64 dot-product path.

### What Is Still A Hypothesis

Hypotheses to prove:

- The blank A510/A715/X3 feature strings are actually hurting generated code on Thor.
- `Use LLVM CPU` blank/generic prevents useful dot-product/int8 codegen for SPU patterns.
- Correcting feature strings or adding Arm64-specific SPU patterns improves real games.
- A newer upstream RPCS3 core already solved some of this and we mainly need to merge it.

The report should not claim these are proven speedups yet. They are unusually promising experiments.

## Novel Perf Idea: Thor Feature Doctor

Thor Feature Doctor would be a diagnostic and tuning layer that answers four questions:

1. What CPU cores and ISA features does Thor actually expose?
2. What CPU target/features does RPCSX pass into LLVM for PPU and SPU recompilation?
3. Which SPU opcodes and compiled kernels dominate this game?
4. Does changing target features or merging newer Arm64 SPU codegen measurably improve cold compile, warm runtime FPS, or stutter?

### Feature Doctor Output

Example output from the app:

```text
Device: AYN Thor
SoC: kalama / Snapdragon 8 Gen 2
Cores:
  CPU0-2 Cortex-A510  features: dotprod=?, i8mm=?, fp16=?, bf16=?
  CPU3-4 Cortex-A715  features: dotprod=?, i8mm=?, fp16=?, bf16=?
  CPU5-6 Cortex-A710  features: dotprod=?, i8mm=?, fp16=?, bf16=?
  CPU7   Cortex-X3    features: dotprod=?, i8mm=?, fp16=?, bf16=?
LLVM target:
  Use LLVM CPU: ""
  resolved CPU: generic-aarch64?
  resolved features: ...
SPU hot ops:
  SUMB count: ...
  GB/GBH/GBB count: ...
  fallback vector patterns: ...
Verdict:
  Thor is missing explicit feature target data. Run benchmark A/B.
```

This is nerdy internally, but user-facing it can become one simple line:

```text
Thor optimized codegen: Ready / Generic / Unknown / Needs benchmark
```

## Experiments To Run

### Experiment 1: Runtime Feature Probe

Add a native export that reports:

- `/proc/cpuinfo` implementer and part IDs.
- `getauxval(AT_HWCAP)` and `getauxval(AT_HWCAP2)` feature bits.
- AsmJit CPU feature detection if available.
- Current `Use LLVM CPU`.
- Current LLVM target triple and feature string used by PPU/SPU compilation if accessible.

Goal: know if Thor exposes dot-product/int8 matrix features and whether the core sees them.

### Experiment 2: AArch64 CPU Table Patch

Create a branch experiment that fills candidate feature strings for:

- Cortex-A510
- Cortex-A715
- Cortex-X3

Do not ship this as default until validated. Candidate features must be checked against Arm docs, Android HWCAP bits, and LLVM support. The right patch may be:

- set correct CPU names only,
- set feature strings,
- or stop relying on this table and ask LLVM/runtime probing directly.

Benchmark:

- PPU compile time.
- SPU compile time.
- warm runtime FPS.
- crashes or cache invalidation.
- whether cache object names change.

### Experiment 3: SPU Opcode Heatmap

Instrument SPU recompilation to count hot opcodes/patterns per game:

- `SUMB`
- `GB`
- `GBH`
- `GBB`
- SPU reservation/busy-wait patterns
- SPURS-heavy sections

This tells us which games could benefit from Arm64 dot-product/pattern work. It also avoids optimizing a cute opcode that nobody's game is actually hammering.

### Experiment 4: Upstream Arm64/SPU Delta Tracker

Add a small report script that compares our bundled core against upstream RPCS3 for files like:

- `SPULLVMRecompiler.cpp`
- `SPUCommonRecompiler.cpp`
- `CPUTranslator.*`
- `AArch64Common.*`
- `AArch64JIT.*`
- `Thread.cpp`

Track upstream PR/release keywords:

- `AArch64`
- `ARM`
- `SPU`
- `SUMB`
- `GBB`
- `GBH`
- `dotprod`
- `i8mm`
- `busy_wait`
- `SPURS`

Goal: know whether Thor performance work should be custom invention or just aggressive upstream syncing.

### Experiment 5: ADPF First, Affinity Second

Android's Performance Hint docs say the OS can decide core/frequency behavior from reported work duration and target duration. For a public app, that advice is right. For our Thor fork, we can be more opinionated, but ADPF should still be the first layer:

- ADPF session for gameplay/render.
- ADPF session for compile/cache preparation.
- Thermal headroom sampling.
- Then optional Thor masks for heavy work.

This makes scheduler experiments safer. If Android ignores affinity, ADPF may still help; if affinity works, ADPF may still improve ramp-up and reduce heat waste.

## Potential Payoff

| Idea | Cold compile | Warm FPS | Stutter | Risk | Notes |
| --- | ---: | ---: | ---: | ---: | --- |
| Feature probe only | None | None | None | Low | Required to stop guessing. |
| Correct AArch64 feature strings | Low-medium | Low-medium | Low-medium | Medium | Could help LLVM codegen; could alter cache identity. |
| Arm64 SPU pattern merge/add | Low | Medium | Medium | Medium-high | Best upside if hot SPU ops map to dot-product/int8 instructions. |
| SPU opcode heatmap | None | None | None | Low | Tells us where to optimize. |
| ADPF compile/gameplay sessions | Low-medium | Low-medium | Low-medium | Low | OS behavior varies; still likely worth it. |
| Per-class Thor scheduler | Low-medium | Medium | Medium | Medium-high | Useful after thread classes and codegen are understood. |

## Why This Is More Novel Than A Preset

Most Android emulator tuning stops at:

- use performance mode,
- pin big cores,
- cap compile threads,
- try another GPU driver,
- keep caches warm.

Feature Doctor goes deeper:

- It checks whether Arm64 LLVM codegen is actually specialized for Thor.
- It connects PS3 SPU opcode patterns to Snapdragon ISA features.
- It tells us whether a game is slow because of scheduling, cache, shader/RSX, or missed Arm64 codegen.
- It gives us a repeatable upstream-sync target instead of random core hacking.

This is the part people may not have thought through for Thor specifically: Snapdragon 8 Gen 2 is not just "fast enough Android." It has a very particular Armv9 big/little layout, and PS3 SPU recompilation has very particular vector/int8-ish patterns. The interesting work is matching those two facts.

## Recommended Next Implementation

Do this next, before another speed knob:

1. Add `ThorFeatureDoctor` native export and UI/log surface.
2. Report CPU parts, HWCAP/HWCAP2 features, current LLVM CPU target, and current core hash.
3. Add SPU compile counters for `SUMB`, `GB`, `GBH`, and `GBB`.
4. Add an internal benchmark screen that runs:
   - generic LLVM target,
   - candidate Thor feature target,
   - current Thor preset,
   - optional scheduler mode.
5. Diff upstream RPCS3 Arm64/SPU files and report whether our core is missing relevant changes.

Only after that should we ship any "Thor Optimized Codegen" toggle.

## Open Questions

- Does Android on Thor expose dot-product/i8mm/bf16 features through HWCAP consistently across all core types?
- Does LLVM already infer the right features when `Use LLVM CPU` is blank?
- Does RPCS3 cache naming include enough target-feature data that experiments will invalidate existing caches?
- Are the hottest Thor games limited by SPU codegen, PPU compile, RSX/Vulkan, shader cache, or storage?
- Are upstream RPCS3 Arm64 SPU improvements already ahead of this bundled core?

## Bottom Line

The creative performance path is:

```text
measure Thor CPU features -> verify LLVM target features -> count hot SPU patterns ->
merge/add Arm64 SPU codegen -> then schedule the optimized threads intelligently
```

That is more realistic than a fantasy GPU-offloaded Cell emulator, and more novel than another "big core mode." It gives the fork a real identity: **a Thor-aware PS3 emulator experiment that understands both Cell/SPU translation and Snapdragon 8 Gen 2's Arm64 feature set.**
