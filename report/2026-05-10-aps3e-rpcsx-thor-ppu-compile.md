# 2026-05-10 APS3E, RPCSX Easy, and Thor PPU Compile Notes

## Short Answer

APS3E is not faster because it magically avoids PS3 emulation work. It is an Android RPCS3-derived port with more Android-specific native control exposed in the app: default LLVM compile thread limits, native CPU detection, selectable LLVM CPU target, thread affinity masks, cache import/export plumbing, and a direct native PPU cache precompile hook.

RPCSX Easy currently wraps a prebuilt RPCSX core through JNI. The UI can read/write core settings and already starts a compilation queue processor, but it does not yet provide a simple "Thor performance preset" or a visible per-game "prepare/build PPU cache" workflow. If the native RPCSX library does not expose direct PPU precompile and thread-affinity controls, those need core/API changes.

The main Thor fix is not one single setting. It is:

1. Keep PPU/SPU LLVM caches on fast internal storage.
2. Cap LLVM compile threads around 4 first, then benchmark 3, 5, and 6.
3. Add runtime-detected Snapdragon 8 Gen 2 presets.
4. Add explicit PPU cache prepare/status UI per game.
5. Add native thread affinity and Android performance hints if the core lacks them.
6. Preserve/label caches when core version, game update, firmware, or LLVM CPU target changes.

## Sources Checked

- APS3E GitHub: <https://github.com/aenu1/aps3e>
- APS3E site: <https://aenu.cc/aps3e/>
- APS3E source at local research clone commit `b5ae1af50d5e2f3b705506e7380a4504e086840b`
- RPCS3 default settings wiki: <https://wiki.rpcs3.net/index.php?title=Help:Default_Settings>
- RPCS3 configurations wiki: <https://wiki.rpcs3.net/index.php?title=Help:Configurations>
- RPCS3 FAQ PPU cache notes: <https://wiki.rpcs3.net/index.php?title=Help:Frequently_Asked_Questions>
- Qualcomm Snapdragon 8 Gen 2 product page: <https://www.qualcomm.com/smartphones/products/8-series/snapdragon-8-gen-2-mobile-platform>
- Qualcomm Snapdragon 8 Gen 2 launch notes: <https://www.qualcomm.com/news/onq/2022/11/new-snapdragon-8-gen-2-8-extraordinary-mobile-experiences-unveiled>
- Snapdragon 8 Gen 2 core breakdown reference: <https://www.notebookcheck.net/Qualcomm-Snapdragon-8-Gen-2-Processor-Benchmarks-and-Specs.670032.0.html>
- Android `PerformanceHintManager`: <https://developer.android.com/reference/android/os/PerformanceHintManager>
- Android NDK Performance Hint Manager: <https://developer.android.com/ndk/reference/group/a-performance-hint>
- Android sustained performance mode: <https://source.android.com/docs/core/power/performance>
- Android Game Mode API: <https://developer.android.com/games/optimize/adpf/gamemode/gamemode-api?hl=en>

## Why PPU Compiles Feel So Bad On Thor

RPCS3's default PPU decoder is LLVM Recompiler. RPCS3 documents that it recompiles the game's executable before first run, and that this is the fastest runtime option. That first-run win comes with a big up-front LLVM compile cost. The FAQ also points to `Create PPU Cache` as a way to collect PPU module cache ahead of smoother use.

Thor's Snapdragon 8 Gen 2 is a heterogeneous mobile CPU, not a desktop CPU. The public 8 Gen 2 layout is one prime core, four performance cores, and three efficiency cores. The common core breakdown is:

- 1x Cortex-X3
- 2x Cortex-A715
- 2x Cortex-A710
- 3x Cortex-A510

The expensive part is that LLVM compile bursts are CPU-heavy, memory/cache-heavy, and not graphics-limited. If compile workers spill onto the A510 efficiency cores or Android keeps the process at ordinary scheduler priority, compile time can look absurd. If too many compiler threads run at once, the device can also heat/throttle and become slower than a smaller, steadier worker count.

On top of that, PPU cache identity is sensitive to game executable hash, emulator/core version, firmware/module set, settings, and LLVM CPU target. APS3E's RPCS3-derived PPU cache object name includes the configured LLVM CPU string, so changing `Use LLVM CPU` can invalidate or bypass old cache objects.

## What APS3E Is Doing Differently

APS3E is a full Android port built from RPCS3 source, not just a UI wrapper around a downloaded `.so`. Its public page says it is built from RPCS3 source and follows GPLv2. The GitHub repository is mostly native C/C++.

Important APS3E findings from commit `b5ae1af`:

- `app/src/main/assets/config/config.yml` defaults `PPU Decoder` to `Recompiler (LLVM)`, `PPU Threads` to `2`, `Max LLVM Compile Threads` to `4`, `LLVM Precompilation` to `true`, `SPU Decoder` to `Recompiler (LLVM)`, and `SPU Cache` to `true`.
- `app/src/main/cpp/rpcs3/rpcs3/Emu/system_config.h` adds config entries for `Thread Affinity Mask` with separate PPU, SPU, and RSX masks.
- `app/src/main/cpp/rpcs3/Utilities/Thread.cpp` routes PPU/SPU/RSX thread classes through those masks and calls `sched_setaffinity` on Android.
- `app/src/main/cpp/cpuinfo.cpp` reads `/proc/cpuinfo` and `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq`, maps Cortex parts including `cortex-a510`, `cortex-a710`, `cortex-a715`, and `cortex-x3`, and exposes core names/frequencies to Java.
- `app/src/main/java/aenu/aps3e/EmulatorSettings.java` exposes `Use LLVM CPU`, `Max LLVM Compile Threads`, `Thread Scheduler Mode`, and a visual per-core affinity mask editor.
- `app/src/main/cpp/aps3e_emu.cpp` has a direct `precompile_ppu_cache(path, fd)` wrapper around `Emu.PrecompilePPUCache`.
- `app/src/main/java/aenu/aps3e/PPUCacheBuildService.java` queues PPU cache builds in a foreground service.
- `app/src/main/java/aenu/aps3e/UserDataActivity.java` has PPU cache import/export management for `.obj.gz` cache files.
- The game menu's `Create PPU Cache` item is commented out in the currently checked source, so the native/service plumbing exists but the visible menu action is disabled there.

That means APS3E is better positioned for Android performance because it can steer native threads and cache workflows directly. It is not proof that its defaults are perfect. It is proof that our fork needs first-class Thor presets and native hooks instead of burying these controls in generic advanced settings.

## What RPCSX Easy Already Has

Local repo observations:

- `app/src/main/cpp/native-lib.cpp` dynamically loads RPCSX exports such as `_rpcsx_processCompilationQueue`, `_rpcsx_startMainThreadProcessor`, `_rpcsx_settingsGet`, and `_rpcsx_settingsSet`.
- `app/src/main/java/net/rpcsx/MainActivity.kt` starts `startMainThreadProcessor()` and `processCompilationQueue()` background threads after core initialization.
- `app/src/main/java/net/rpcsx/ui/settings/SettingsScreen.kt` can display the native settings JSON and write settings through `settingsSet`.
- `RPCSX.rootDirectory` is set to the app external files directory. Game files can live on SD, but PPU/SPU/shader caches should be kept on fast internal app storage if possible.

The missing pieces are product/UI decisions and possibly native exports:

- No simple "AYN Thor / Snapdragon 8 Gen 2" performance preset.
- No game-detail "Prepare PPU cache" button with progress/status.
- No cache manager showing warm/cold PPU cache status per title.
- No Android-side CPU topology detection in this repo.
- No visible native API in this wrapper for direct `precompile_ppu_cache(path/fd)`.
- No visible native API in this wrapper for PPU/SPU/RSX affinity masks, unless the downloaded core's settings JSON already exposes equivalent keys.

## Thor-Specific Preset Hypothesis

Do not hardcode logical CPU IDs forever. Detect them on device from `/proc/cpuinfo` and `cpuinfo_max_freq`, then build masks dynamically. For the common Snapdragon 8 Gen 2 layout, a likely logical grouping is:

- Efficiency: A510 cores, usually CPUs `0-2`, mask `0x07`
- Performance plus prime: A715/X3/A710 cores, often CPUs `3-7`, mask `0xF8`
- A715 plus X3 subset, often CPUs `3-5`, mask `0x38`
- A710 pair, often CPUs `6-7`, mask `0xC0`

Initial experiments:

| Preset | Max LLVM compile threads | PPU mask | SPU mask | RSX mask | Why |
| --- | ---: | ---: | ---: | ---: | --- |
| Baseline | current | current | current | current | Capture current pain. |
| Thor Safe | 4 | `0xF8` | `0xF8` | `0xF8` | Keep heavy work off A510 without overthinking. |
| Thor Balanced | 4 | `0x38` | `0xD8` | `0x20` | Give PPU prime/A715, SPU four perf cores, RSX prime. Test only. |
| Thor Compile Burst | 5 | `0xF8` | `0xF8` | `0xF8` | Faster first compile if thermals hold. |
| Thor Cool | 3 | `0xD8` | `0xD8` | `0x20` | Lower heat, maybe better sustained compile. |

`Use LLVM CPU` should not be forced blindly. APS3E exposes `cortex-x3`, `cortex-a715`, `cortex-a710`, `cortex-a510`, and fallback targets, but its default is blank. Since generated code may execute on more than one core type, forcing `cortex-x3` is only safe after we prove all runtime cores support the emitted instructions or also pin the generated-code threads to compatible cores. Start with blank/generic, then benchmark `cortex-a710`, `cortex-a715`, and `cortex-x3` separately.

## Recommended Implementation Order

### Phase 1: UI Repo Only

Add a Performance page:

- "Device preset: AYN Thor / Snapdragon 8 Gen 2"
- "Prepare game cache" action on each game detail page
- Per-game badges: `PPU cache missing`, `PPU cache ready`, `cache stale after core/settings change`
- "Cache storage: internal app storage" warning if caches are on slower external/SD storage
- Benchmark logging: compile start/end, game ID, PPU hash if known, core version, LLVM CPU, max LLVM threads, scheduler mode, storage path, and battery/thermal state if available

Use `settingsGet/settingsSet` where the native settings JSON exposes:

- `Core@@Max LLVM Compile Threads`
- `Core@@LLVM Precompilation`
- `Core@@PPU Decoder`
- `Core@@SPU Decoder`
- `Core@@Thread Scheduler Mode`
- `Core@@Use LLVM CPU`

If the current RPCSX core exposes affinity mask keys, use them. If it does not, do not fake it in UI. Mark it as a native TODO.

### Phase 2: Native/Core Hooks

Add or expose these RPCSX native exports:

- `getCpuCoreInfo()` with core id, name, max MHz, and current online state
- `setThreadAffinityPreset(ppuMask, spuMask, rsxMask)`
- `precompilePpuCache(path or fd, progressId)`
- `getPpuCacheStatus(gamePath/titleId)`
- `exportPpuCache(titleId)` and `importPpuCache(zip)`

Add Android-side performance integration:

- Use a foreground service for long PPU cache builds.
- Use Android `PerformanceHintManager`/NDK `APerformanceHint` for native frame/runtime threads where available.
- For long gameplay sessions, consider Android sustained performance mode if the device reports support.
- Declare/support Android Game Mode performance mode so Thor/Android can treat the app like a game.

### Phase 3: Cache Manager

Build a cache manager that is boring and obvious:

- Game name, title ID, cache size, cache age, core version, LLVM CPU target.
- Import/export per-game PPU cache ZIPs for personal device migration.
- Delete stale cache.
- Rebuild cache.

Do not bundle generated PPU caches in the APK. They are generated from user game executables and are tied to settings/core version. Bundling them is a legal and technical mess.

## Benchmark Plan

Use the same game dump, firmware, and app data state for RPCSX Easy and APS3E:

1. Cold cache first boot from internal storage.
2. Cold cache first boot with game on SD but cache on internal.
3. Warm cache second boot.
4. Thor Safe preset.
5. Thor Balanced preset.
6. Thor Compile Burst preset.
7. Generic LLVM CPU vs `cortex-a710` vs `cortex-a715` vs `cortex-x3`.

Record:

- Wall-clock PPU compile time.
- Wall-clock SPU cache build time if separate.
- First playable frame time.
- Runtime FPS after cache.
- Thermal throttling or sustained clocks.
- Whether the cache is reused on next boot.
- Whether changing LLVM CPU invalidates cache.

## Bottom Line

APS3E feels faster because it is closer to the native RPCS3 core and already exposes Android-specific levers: compile thread limit, CPU target, affinity, and cache tooling. PPU compile still has to happen with LLVM unless we switch to interpreter or defer compilation, both of which have tradeoffs.

For RPCSX Easy on AYN Thor, the most useful next move is a real Thor performance/cache workflow: detect the CPU layout, cap compile threads, keep caches on internal storage, give users a clear per-game "Prepare cache" button, and add native affinity/precompile exports where the current RPCSX library does not already expose them.
