# RPCSX for AYN Thor Experiment Agent Notes

## Repo And Git

- Work directly on `master` only for this repo. Do not create or switch to feature branches for this fork.
- Remote push target is SSH: `git@github.com:noeldvictor/rpcsx-ui-android-thor.git`.
- Commit completed work to `master` and push only `origin master`.
- Commit and push frequently during Thor core work. Make a checkpoint after each buildable native/APK slice, after each on-device install/smoke pass, and before starting risky upstream ports such as SPU reduced-loop or reservation-notifier rewrites.
- Do not fork extra RPCSX repos for this project; keep Android-side and native/core experiment work in this repo unless the user asks otherwise.
- Public positioning: this is a personal-use, AI-assisted/vibe-coded AYN Thor experiment. Do not present it as official RPCSX, official AYN, stable, or support-backed.

## Vendored RPCSX Core Source

- The RPCSX core source is checked into this repo as plain files at `app/src/main/cpp/rpcsx`.
- It is not a Git submodule. Edit the vendored core files directly for Thor experiments.
- The custom Thor core is in this repo as source, not only on the device and not only in a downloaded APK. Core changes under `app/src/main/cpp/rpcsx` are real repo changes; the debug APK packages them into `lib/arm64-v8a/librpcsx-android.so`.
- The upstream core's third-party source dependencies are pinned as root repo submodules under `app/src/main/cpp/rpcsx/3rdparty` and `app/src/main/cpp/rpcsx/rpcs3/3rdparty`.
- These dependency submodules use upstream SSH URLs and exact SHAs from the vendored RPCSX commit. They are source pins, not extra forks in this GitHub account.
- Initial vendored upstream commit: `e27926d6296e2ce4bd5b0775cb4e4423d9e7cdb6` from `git@github.com:RPCSX/rpcsx.git`.
- The vendored tree has its own `UPSTREAM.md` with the upstream commit and sync notes.
- Refresh the core source with `tools/sync_rpcsx_core.ps1` from the Android repo root; keep local Thor experiment changes in this repo.
- Hydrate core dependencies with `tools/hydrate_rpcsx_core_deps.ps1`. On Windows, keep `git config core.longpaths true` because SPIRV-Cross and LLVM contain long test/reference paths.
- Do not commit generated native build output, downloaded prebuilt tarballs, APKs, `.cxx`, Gradle caches, or runtime PPU/SPU/shader caches.
- The default Gradle app build uses `app/src/main/cpp/CMakeLists.txt` for the Android JNI wrapper and now bundles the vendored full core by default. The full core Android build entry is `app/src/main/cpp/rpcsx/android/CMakeLists.txt`.
- Java loads the wrapper as `librpcsx-ui-jni.so`. A source-built/bundled core should package as `librpcsx-android.so`, and `MainActivity` will use it when no custom/downloaded core path is configured.
- In this fork, `MainActivity` treats a valid bundled `librpcsx-android.so` as authoritative and rewrites `rpcsx_library` to the APK native-lib path on startup. This keeps stale saved `/data/app/...` paths or old updater cores from hiding local source-core changes or triggering install prompts on Thor.
- Build source-core packaging with the normal `.\gradlew.bat :app:assembleDebug` after dependency hydration. For fast UI-only iteration, opt out with `-PbuildBundledRpcsxCore=false` or `RPCSX_BUILD_BUNDLED_CORE=0`.
- Current source-core status on 2026-05-10: `.\gradlew.bat ':app:configureCMakeDebug[arm64-v8a]'` succeeds, and `.\gradlew.bat :app:assembleDebug` succeeds after dependency hydration. The bundled debug APK includes `lib/arm64-v8a/librpcsx-android.so` plus `librpcsx-ui-jni.so`; the source-core build is slow and noisy with upstream warnings.
- Treat core changes as first-class repo changes: edit the vendored files directly, test where possible, then commit and push on `master`.

## Upstream RPCS3 Arm64 Mining

- Do not treat RPCS3's lack of official Android/iOS support as a reason to ignore its arm64 work. Treat upstream RPCS3 arm64 as a playbook to mine, then adapt the ideas to Android lifecycle, JNI/export boundaries, memory pressure, AYN Thor thermals, and Adreno/Vulkan driver reality.
- Current core rework direction: use RPCS3 ARM64 on Linux/Rocknix as the critical behavioral/performance reference, not generic Android PS3 forks. Keep the Android app shell and Thor UX, but make the native core converge toward the proven upstream ARM64 Linux path in small buildable slices.
- Primary project goal is not merely "make it boot." Boot stability is the baseline; the active goal is better performance, compatibility, and visual/audio quality on AYN Thor. Keep mining upstream RPCS3 for real CPU/GPU/runtime wins, but land them in measured slices with a fast rollback path when a speed import corrupts guest state or regresses boot.
- Do not abandon a promising upstream performance idea just because the first direct port crashes. First restore a known-good APK, document the failure signature, then retry the idea with instrumentation, feature gates, or a narrower implementation.
- Local upstream comparison checkout: `C:\Users\leanerdesigner\Documents\New project 6\rpcs3-upstream`. At the last inspection it had `origin/master` at `021f16f` (`rsx: Fix swapped width/height in NV309E_SET_FORMAT decoder`). The Android vendored RPCSX repo was at `e309e1e` (`Remove risky Eternal Sonata boot settings`).
- Before inventing Thor-specific native optimizations, diff the vendored core against upstream RPCS3 for `rpcs3/Emu/CPU/Backends/AArch64/*`, `rpcs3/Emu/CPU/CPUTranslator.*`, `rpcs3/Emu/Cell/SPULLVMRecompiler.cpp`, `rpcs3/Emu/Cell/SPUCommonRecompiler.cpp`, `rpcs3/Emu/RSX/*`, `rpcs3/util/sysinfo.*`, and VM/page-size code.
- Imported dotprod target: upstream RPCS3's `m_use_dotprod` / `utils::has_dotprod()` paths for AArch64 `sdot`/`udot` in SPU `GB`, `GBH`, `GBB`, and `SUMB` are now the local reference. Keep the Android HWCAP gate and JIT target attributes in sync so Thor's Snapdragon 8 Gen 2 can actually lower those paths when Android reports `HWCAP_ASIMDDP`.
- Imported slice 2026-05-12: shared ARM64 feature gates in `util/sysinfo.*` now cover `NEON`, `SHA3`, `DOTPROD`, `SVE`, and `SVE2`; Android JIT SVE sanitization should use those shared core gates instead of local one-off HWCAP checks.
- Imported slice 2026-05-12: `JITLLVM` now passes upstream-style AArch64 target attributes (`+/-sha3`, `+/-dotprod`, `+/-sve`, `+/-sve2`) into LLVM and uses `cortex-a78` instead of `cortex-a34` as the unknown Android ARM64 fallback. Do not remove this without replacing it with a better Thor/Rocknix-aware CPU selection path.
- Imported slice 2026-05-12: ARM64 JIT telemetry now logs each unique LLVM engine target as `LLVM AArch64 target: cpu=... triple=... attrs=...`, and Thor Feature Doctor shows `LLVM ARM64 target attrs`. Use this before/after SPU or PPU imports to prove the intended CPU/features are active on device.
- Imported slice 2026-05-12: SPU worker setup follows the upstream ARM64 rule that ASMJIT is not an ARM64 SPU backend. On ARM64, ASMJIT requests fall back to LLVM with a warning, and Thor-managed per-game configs rewrite `SPU Decoder: Recompiler (ASMJIT)` to `Recompiler (LLVM)`.
- Imported slice 2026-05-12: the Android core now exports `_rpcsx_preparePpuCache`, backed by the existing PPU compilation path, so the UI cache-warm button can actually prebuild PPU cache for a stopped/ready title instead of only telling the user to boot once.
- Imported slice 2026-05-12: Thor now enables the native RPCS3 scheduler when the Android performance-core mask applies, with CPU3 shared/general, CPU4 PPU, CPU5-6 SPU, and CPU7 RSX. Android `thread_ctrl::get_affinity_mask()` intersects class masks with the thread's current runtime affinity so configured low cores do not leak back into PPU/SPU/RSX masks after the Java-side `0xF8` pin.
- Imported slice 2026-05-12: Android/Linux SPU reservation waits now honor the upstream-style `SPU Reservation Busy Waiting Enabled` gate instead of hard-disabling busy waits under `__linux__`. Thor profile v8 enables it at 100% and Feature Doctor reports the active gate/percentage. Watch thermals and SPURS-heavy games such as Eternal Sonata when tuning this.
- Imported slice 2026-05-12: SPU GETLLAR spin prediction now uses upstream's shared `evaluate_spin_optimization(...)` helper, tracks `last_getllar_lsa`, and avoids classifying stack-output GETLLAR patterns as tight wait loops. This keeps the new busy-wait path less reckless on SPU code that is not actually spinning.
- Imported slice 2026-05-12: `SPU_RdEventStat` now carries upstream-style reservation wait history (`eventstat_*`, 16-entry wait history, timed busy-wait fallback) while preserving the local Android reservation-notifier API. This is the main SPURS/Eternal Sonata-facing scheduler import; validate boot/gameplay and thermals before raising related percentages further.
- Imported slice 2026-05-12: latest safe SPU/RSX parity pass from refreshed upstream `021f16f`. Ported SPU LLVM LQX/STQX address reuse, CEQHI/CEQI compare-negation peepholes where compatible, SPU channel wait/occupy bit separation and stopped-wait cleanup, removal of RCHCNT loop handling for `SPU_WrOutMbox` / `SPU_WrOutIntrMbox`, and the NV309E RSX width/height decoder fix. Built native/APK and installed on Thor at `2026-05-12 17:23:24`.
- Imported slice 2026-05-12: upstream LLVM/SPU optimizer cleanup. `CPUTranslator::bitcast(...)` now reuses compatible existing bitcasts and carries the later upstream use-list guard in `erase_stores(...)`; SPU LLVM now marks SPU local-storage loads/stores and SPU thread-context loads/stores with separate alias/noalias metadata. This is a compile/codegen quality import for ARM64 SPU LLVM, built native/APK and installed on Thor at `2026-05-12 17:43:51`.
- Imported slice 2026-05-12: ISO streaming now handles upstream-style multi-extent directory entries and reads direct ISO files across extent boundaries instead of treating large payloads as one broken/whole-file read. Keep this as the baseline for Eternal Sonata's multi-GB archive files.
- Imported slice 2026-05-12: Android RAM pressure telemetry is now in the native core. `utils::get_memory_usage()` reads Android/Linux `/proc/meminfo`, PERF logs include used/free/peak RAM, emergency exits annotate low-memory conditions, and Android fatal reports include RAM totals. Use this before guessing at OOM/LMK behavior.
- Imported slice 2026-05-12: Vulkan scratch-buffer and DMA-barrier parity pass from upstream RSX fixes. Scratch allocations now carry explicit destination stage/access, texture flush DMA has a post-transfer barrier, tiling compute/transfer access masks match the producer, and unsafe GPU zero-copy skips userptr sources. This is an Adreno correctness/perf foundation, not an Eternal Sonata FPS cure by itself.
- Imported slice 2026-05-12: Vulkan shader interpreter can use the existing deferred pipeline compiler for experimental preload work, avoids duplicating interpreter uniform tables per variant, and logs interpreter cache misses over 1000 ms with the compiler option mask. The representative interpreter pipeline preload is disabled by default on Thor because the first APK felt dramatically slower; do not re-enable it for FPS testing without a narrow opt-in and timing logs.
- Deferred upstream slice: full async interpreter variant recompilation from RPCS3 commits `d519571`, `d93f5f9`, and `4e16032` is only partially portable until the local interpreter cache is refactored from raw `glsl::program*` / `unique_ptr` ownership to the upstream shared program cache, shader-object split, pipeline flags, and variant metadata. Do that ownership refactor before trying to replace live interpreter programs from worker callbacks.
- Deferred upstream slice: Vulkan data-heap/window UBO work is not directly portable yet because this fork lacks upstream's newer `VkDescriptorBufferInfoEx`, `address_range64`, `ex.h`, and `rsx::data_heap` plumbing. Revisit after the descriptor-buffer/data-heap refactor, not as a blind cherry-pick.
- Imported slice 2026-05-12: SPU reduced-loop work has started with detection-only instrumentation. `debug.rpcsx.thor.spu_reduced_loop_detect=1` scans analyser basic-block topology for upstream-style reduced-loop candidates and logs `Reduced Loop Candidate (detect-only)` with loop PC/end, block/instruction counts, and function hash. This must not add `inst_attr::reduced_loop` or change LLVM emission yet; use `.\tools\set_thor_logging.ps1 -Mode ReducedLoop` for short Eternal Sonata captures, then return to `Normal` or `Quiet`.
- Remaining major Eternal Sonata target: upstream SPU reduced-loop detection/emission series (`a863e94`, `2e4ee9c`, `37a07ae`, `619fe7b`, `02eb549`, later analyzer fixes). Direct patch application fails against the vendored core because it needs analyzer/opcode/recompiler-struct migration; port as a dedicated gated slice with build checkpoints and a fast rollback.
- Attempted slice 2026-05-12: VM reservation notifiers were direct-ported toward upstream-style reservation-time buckets, then rolled back. The current stable Thor APK uses the older address-only notifier API again; do not treat rtime-aware notifier calls as active local behavior.
- Rolled-back slice 2026-05-12: the first rtime-aware reservation notifier and SPU PUTLLC / 128-byte store parity attempts caused deterministic `SIGBUS BUS_ADRALN` crashes in `PPU[0x1000000]` at PC/fault `0xd0020d77` on BLUS30161 after SPU runtime build. Revisit these as performance-quality targets, but only behind instrumentation/feature gates that log reservation address, rtime, waiter bucket/index, old/new hashes, and GETLLAR/PUTLLC loop identity before changing wakeup or partial-line store behavior.
- Keep using the existing Thor Feature Doctor output in `_rpcsx_systemInfo()` to verify HWCAP/HWCAP2 before enabling imported Arm64 fast paths. The important feature flag for the dot-product SPU work is `ASIMDDP/dotprod`; `I8MM` and `BF16` are also worth recording, but do not imply SVE.
- Upstream arm64 lessons to mine include LLVM tail-call/JIT gateway handling, AArch64 signal/exception decoding, 16K page-size and dirty-page tracking behavior, non-x86 RSX fallback fixes, and Linux/macOS/Windows-on-ARM build/runtime fixes. Android may need different wrappers, but the emulator-side ideas are still relevant.
- Avoid framing this work as "RPCS3 for Android exists." The correct framing is: this fork borrows proven upstream RPCS3 arm64 compiler/runtime techniques and ports/adapts them into a personal AYN Thor Android experiment.
- If an upstream arm64 idea depends on platform APIs unavailable on Android, document the missing API and build an Android-specific substitute where practical. Do not discard the idea until the emulator-level mechanism has been understood.

## Local Build Environment

- Repo path: `C:\Users\leanerdesigner\Documents\New project 6\rpcsx-ui-android`
- Java: `C:\Users\leanerdesigner\.codex\jdks\jdk-17`
- Android SDK: `C:\Users\leanerdesigner\AppData\Local\Android\Sdk`
- ADB: `C:\Users\leanerdesigner\AppData\Local\Android\Sdk\platform-tools\adb.exe`

Use these environment variables for Gradle commands in PowerShell:

```powershell
$env:JAVA_HOME='C:\Users\leanerdesigner\.codex\jdks\jdk-17'
$env:ANDROID_HOME='C:\Users\leanerdesigner\AppData\Local\Android\Sdk'
```

Useful verification commands:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\tools\hydrate_rpcsx_core_deps.ps1
.\gradlew.bat :app:assembleDebug -PbuildBundledRpcsxCore=false
```

## Thor Debug Capture Workflow

- For black screens, crashes, or weird per-game boot behavior, use the Thor debug tools before guessing.
- Control device logging with `.\tools\set_thor_logging.ps1 -Mode Quiet|Normal|Verbose|ReducedLoop|Status`. Use `Quiet` for FPS/performance sweeps, `Normal` for ordinary crash/boot repros, `ReducedLoop` only for short SPU compiler captures, and `Verbose` only for short targeted syscall captures because it enables PPU syscall usage stats.
- The logging tool sets Android properties over ADB: `debug.rpcsx.thor.logcat`, `debug.rpcsx.thor.syscall_stats`, `debug.rpcsx.thor.spu_reduced_loop_detect`, `log.tag.RPCS3`, and `log.tag.RPCSX-UI`. The bundled core polls/logging properties at runtime where supported, so Codex can turn logcat/syscall-stat pressure on or off without rebuilding after this APK is installed.
- Do not leave `debug.rpcsx.thor.syscall_stats=1` during FPS testing. Eternal Sonata can hit hundreds of thousands of timer/semaphore/SPU-group syscalls in a few minutes, and stats/logging can become its own benchmark poison.
- Live debug loop while the user plays: `.\tools\start_thor_debug_stream.ps1 -ClearLogcat -Launch -Label GAME`, then repeatedly run `.\tools\summarize_thor_debug_stream.ps1 -Latest` from Codex while the user reproduces the issue, then `.\tools\stop_thor_debug_stream.ps1 -Latest`.
- Live streams write to ignored `debug-captures/*-stream/` folders. Watch `summary-latest.md`, `logcat-live.txt`, `rpcsx-live-tail.txt`, and `live-summary/now-*.txt`.
- Newer live streams also write `memory-live.txt`; use it to catch RSS/swap growth before Android's low-memory killer takes the app down.
- Clean repro flow: `.\tools\collect_thor_debug.ps1 -Prepare -Launch -Label GAME`, reproduce on Thor, then `.\tools\collect_thor_debug.ps1 -Label GAME`.
- Do not run `-Prepare` after a crash; it clears logcat and destroys the freshest evidence.
- Captures are written under ignored `debug-captures/` folders. Start analysis with `logcat-interesting.txt`, `device-files/cache/RPCSX.log`, `rpcsx-log-errors.txt`, `activity.txt`, and `cache-summary.txt`.
- Classify the failure before changing code: native/app crash, black screen while app is alive, or regression after settings/core/cache/driver/cheat changes.
- For black screen while alive, check whether the title is still compiling, installing game data under `dev_hdd0/game`, waiting on a PS3 dialog, or producing audio without RSX frames.
- If a live stream is active, do not leave it running forever after the repro. Stop it, inspect the final pull, then commit fixes or notes.
- A 2026-05-12 Eternal Sonata live stream at roughly 10-13 FPS showed no OOM/crash, but did show `rsx::thread` plus six SPU threads hot, heavy `sys_timer_usleep`, `sys_semaphore_wait/post`, `sys_event_queue_receive`, and repeated `sys_spu_thread_group_start/join`. Treat this as a SPURS/RSX contention profile first, not a basic boot failure.
- Before trusting an Eternal Sonata FPS result, inspect `config/custom_configs/config_BLUS30161.yml`. A stale full custom config can override Thor defaults, but the attempted managed `Max SPURS Threads: 4` profile caused a black-screen-alive load hang on 2026-05-12. Use `.\tools\push_eternal_sonata_thor_profile.ps1` only as a safe boot rollback; do not reapply SPURS 4 for this game without a separate instrumented test build.
- A live Thor repro on 2026-05-11 caught `BLUS31386` crashing in PPU LLVM precompile with `memory_commit(... errno=12=Out of memory)` and an LLVM `report_bad_alloc_error` tombstone. Treat first-boot full PPU precompilation as risky on Thor; the fork defaults should prefer `LLVM Precompilation=false` and low compile-thread pressure unless a guarded cache-builder mode is being tested.
- A later 2026-05-11 repro showed Android LMK killing `net.rpcsx.easy` near 6 GB RSS while a direct ISO game opened multi-GB `/dev_bdvd/PS3_GAME/USRDIR/archives/*.files` payloads. The vendored ISO device must stream file reads; do not reintroduce whole-file ISO reads or whole-file ISO extraction copies for large game data.

## Device Testing

- Target handheld: AYN Thor Base/Pro/Max.
- Known ADB model string: `AYN_Thor`.
- Debug APK path after assemble: `app\build\outputs\apk\debug\rpcsx-thor-experiment-debug.apk`.
- Android package: `net.rpcsx.easy`.
- Launcher label: `RPCSX for AYN Thor Experiment`.
- Launcher activity: `net.rpcsx.MainActivity`.
- This fork sets `BuildConfig.FORK_BUILD=true`; automatic upstream UI/core update prompts should stay disabled.
- Folder import is intentionally conservative: only loose `.pkg` and `.edat` files are sent to the native installer. Loose `.iso` files under Android external-storage documents are added as direct library entries instead of extracted, because the current core can abort while extracting some ISO directory entries.
- External ISO entries parse `PS3_GAME/PARAM.SFO` and `PS3_GAME/ICON0.PNG` directly from the ISO to populate title IDs, names, cheat matching, and cached cover art.
- The README is source-first: no big public APK download button, no support queue positioning.
- In-game Back is handled by `RPCSXActivity`; it opens the native RPCSX Home Menu on a background thread and then acts as menu back/resume while the menu is up.
- Gameplay Back behavior is hardcoded for Thor/fork builds: Android Back / Thor Back opens the native Home Menu, the core forces pause-during-Home-Menu on, and another Back press injects the native menu back/cancel action so nested pages back up and the root menu resumes gameplay.
- Gameplay-only physical hotkeys are hardcoded for Thor controls: `Select + R1` toggles Fast Forward 2x, `Select + right stick down` quick-saves, and `Select + right stick up` quick-loads the latest per-game savestate. Select is delayed and sent as a normal tap only when no hotkey combo is completed.

Install and launch:

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\rpcsx-thor-experiment-debug.apk
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell pm grant net.rpcsx.easy android.permission.POST_NOTIFICATIONS
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell monkey -p net.rpcsx.easy 1
```

If the launcher or another foreground app steals focus, launch the main activity directly:

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell am start -n net.rpcsx.easy/net.rpcsx.MainActivity
```

If the app does not appear, verify the installed package:

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell pm list packages net.rpcsx.easy
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell cmd package resolve-activity --brief net.rpcsx.easy
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell dumpsys activity activities | Select-String -Pattern 'topResumedActivity|net.rpcsx.easy'
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell pidof net.rpcsx.easy
```

## Native Home Menu / OSD Work

- Android already shows the native RPCSX in-game `Home Menu` over gameplay. Do not build a separate Android/Compose OSD unless the user explicitly asks for a replacement.
- The Android wrapper currently opens that menu through `_rpcsx_openHomeMenu`, surfaced as `RPCSX.instance.openHomeMenu()`. `RPCSXActivity` treats Android/Thor Back as the Home Menu toggle/back control; do not remap controller Circle/B to this Android-only action.
- Adding first-class menu rows such as `Cheats` or `Show FPS` belongs in the native/core Home Menu implementation, or behind a deliberate exported C ABI hook that the Android app can call. Android layout files cannot directly add rows to the existing menu.
- Native Home Menu source now lives in this repo under `app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Overlays/HomeMenu/`.
- The Android-side native export surface for the full core lives at `app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp`; the lightweight dynamic-loader wrapper lives at `app/src/main/cpp/native-lib.cpp`.
- Localized Home Menu IDs live in `app/src/main/cpp/rpcsx/rpcs3/Emu/localized_string_id.h`.
- FPS/performance overlay rendering/reset code lives around `app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Overlays/overlay_perf_metrics.cpp`.
- The vendored Home Menu now has a `Cheats` page source at `app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Overlays/HomeMenu/overlay_home_menu_cheats.cpp`, plus top-level `Fast Forward 2x` and `Show FPS` toggles after Resume/Cheats.
- These vendored Home Menu changes require the source-built/bundled core APK or another core build that includes these files. The default lightweight-wrapper build alone cannot change the native Home Menu.
- The current Home Menu on this fork should include `Resume Game`, `Cheats`, `Fast Forward 2x`, `Show FPS`, `Settings`, `Trophies`, `Take Screenshot`, `Start/Stop Recording`, `SaveState`, `Restart Game`, and `Exit Game`.
- FPS display is already represented in the RPCSX config file under `Video -> Performance Overlay -> Enabled`. Through the Android settings bridge this should be treated as `Video@@Performance Overlay@@Enabled`.
- If only a simple FPS toggle is requested, prefer toggling `Performance Overlay.Enabled` first. Avoid enabling debug overlays or graph-heavy performance views unless the user asks for more metrics.
- Fast Forward 2x is a runtime guest-time speedhack: `_rpcsx_setFastForwardEnabled` changes `Core -> Clocks scale` to `200` and restores the previous value when disabled. It is not a frame-limit uncap, and it may break timing-sensitive games.
- Hotkey save/load uses `_rpcsx_saveState` and `_rpcsx_loadState`; quick-save forces continuous savestate mode so gameplay resumes instead of saving-and-exiting.
- Cheat toggles in the Home Menu should show only the currently running game's cheats, one row per cheat, with controller-friendly toggle behavior.
- Cheat state is persisted in `config/patch_config.yml`; generated patch definitions live under `config/patches/TITLEID_patch.yml`.
- For live cheat toggling, use the existing optional native hook `RPCSX.setPatchEnabled(hash, description, enabled)` when the current core exports it. If the hook is missing or the patch cannot apply live, the menu must clearly say the toggle takes effect after restart.
- Keep OSD wording user-facing: say `Cheats`, `Show FPS`, `Restart needed`, or `Needs first boot`; avoid database/internal words in the menu.

## Cheat Work

- Support offline single-player cheats only.
- Do not help bypass DRM, anti-cheat, or online/multiplayer protections.
- Bundled Aldos/Artemis source lives under `app/src/main/assets/cheats`.
- Bundled SQLite cheat database lives at `app/src/main/assets/cheats/cheats.db`; regenerate it with `python tools/build_cheat_db.py` after changing bundled NCL/imported patch assets.
- Chidreams/RPCS3-ready imported patches live under `app/src/main/assets/cheats/chidreams` and are stored in `cheats.db` as `rpcs3_patch` entries.
- Converted test fixtures live under `app/src/test/resources/cheats/converted`.
- RPCSX/RPCS3 patches require a learned PPU hash; boot a game once, close it, then install fixed-write cheats.
- RPCS3-ready `rpcs3_patch` entries already include PPU hashes and can be installed without the Artemis conversion step.
- AoB cheats are parsed and counted as risky, but should not be installed until native byte validation/scanning exists.

## Recommended Game Settings

- Bundled database: `app/src/main/assets/config/config_database.dat`.
- Current source endpoint: `https://api.rpcs3.net/config/?api=v1`.
- The snapshot is RPCS3-style JSON: `games[TITLEID].config` contains a YAML config snippet.
- Android-side manager: `app/src/main/java/net/rpcsx/config/GameSettingsDatabase.kt`.
- The app seeds and reads a writable local cache at `config/GuiConfigs/config_database.dat` under the RPCSX root so a future/core-side config database reader can find the familiar upstream location.
- Preserve a valid local cache when it is newer than or equal to the bundled APK snapshot; do not stomp refreshed cache data during app startup.
- The game detail card has a refresh icon that downloads `https://api.rpcs3.net/config/?api=v1` into the local cache. Startup should stay offline-friendly and only seed/fallback to the bundled snapshot.
- The game detail screen shows this as `Recommended Settings`, not as database jargon.
- Managed per-game configs are written to `config/custom_configs/config_TITLEID.yml` with the `# RPCSX_THOR_AUTO_SETTINGS` header.
- Never overwrite an existing custom config unless it has the managed header. User-created custom configs win.
- Boot flow applies the recommended config before launch only when the game has a title-ID match and the user has not turned the switch off for that game.

## Per-Game Cache Workflow

- Android-side cache status lives at `app/src/main/java/net/rpcsx/performance/GameCacheRepository.kt`.
- Game detail reads `cache/cache/TITLEID` under `RPCSX.rootDirectory`, counts PPU/SPU/shader entries, shows cache size, and exposes refresh/clear controls.
- RPCSX/RPCS3 stores RSX shader cache below the PPU cache path (`.../ppu-*/shaders_cache/`), so the same per-game compiled-cache root must cover PPU, SPU, and shaders.
- Compiled-cache storage selection lives at `app/src/main/java/net/rpcsx/performance/CacheStorageManager.kt` and is exposed from Settings as `Compiled Cache Storage`.
- The core still expects `RPCSX.rootDirectory/cache/cache`; SD-card selection redirects that app-owned compiled-cache path with a symlink to the selected app-owned external-files directory, covering PPU/SPU/shader cache together.
- Do not offer arbitrary SAF folders for emulator cache until the native core supports URI/document access or a stable cache-directory setting. App-owned storage roots are the safe selectable locations.
- Switching compiled-cache storage can migrate existing cache data and should warn users that SD card cache may be slower and large moves can take minutes.
- The native wrapper has an optional `_rpcsx_preparePpuCache` hook surfaced as `RPCSX.supportsPpuCachePreparation()` and `RPCSX.preparePpuCache(...)`.
- The bundled source-built Thor core exposes `_rpcsx_preparePpuCache`; downloaded/older external cores may still lack it, so keep `supportsPpuCachePreparation()` as the UI gate.
- Do not call private C++ internals like `Emulator::BootGame` or `ppu_precompile` across the wrapper boundary unless the core intentionally exports a stable C ABI.
- Library delete now clears title-ID cache when a title ID is known; keep ISO/file-name fallback for entries without metadata.

## Thor Variant Notes

Base, Pro, and Max share the same CPU/GPU performance target:

| Variant | CPU/GPU | RAM | Storage | App strategy |
| --- | --- | ---: | ---: | --- |
| Base | Snapdragon 8 Gen 2 / Adreno 740 | 8 GB LPDDR5X | 128 GB UFS 4.0 | Same CPU/GPU presets, smaller cache budget, avoid memory-hungry global views. |
| Pro | Snapdragon 8 Gen 2 / Adreno 740 | 12 GB LPDDR5X | 256 GB UFS 4.0 | Default comfort target for testing. |
| Max | Snapdragon 8 Gen 2 / Adreno 740 | 16 GB LPDDR5X | 1 TB UFS 4.0 | More cache/library headroom; do not assume faster PPU compile. |

Thor Lite is Snapdragon 865 / Adreno 650 and is not the PS3 performance target for this fork. Do not apply Snapdragon 8 Gen 2 affinity masks to Lite.

## Thor CPU Notes

The connected AYN Thor reports board/platform `kalama` and this CPU part layout:

- CPUs `0-2`: Cortex-A510, part `0xd46`, mask `0x07`
- CPUs `3-4`: Cortex-A715, part `0xd4d`, mask `0x18`
- CPUs `5-6`: Cortex-A710, part `0xd47`, mask `0x60`
- CPU `7`: Cortex-X3, part `0xd4e`, mask `0x80`
- Heavy work mask for performance plus prime cores: CPUs `3-7`, mask `0xF8`

Use detected topology for presets. Do not assume every Snapdragon 8 Gen 2 device orders logical CPUs the same way.

## Thor Optimization Notes

- Already cleaned up Android-side hotspots: folder scan queues use `ArrayDeque`, URI file copy uses a larger stream buffer, ISO metadata avoids duplicate directory parsing, patch status file checks are cached, and `games.json` saves are debounced.
- Current Thor compile preset lives at `app/src/main/java/net/rpcsx/performance/ThorPerformanceProfile.kt`.
- The preset is applied on AYN/Thor/kalama targets: `Max LLVM Compile Threads=2`, `LLVM Precompilation=false`, `SPU Cache=true`, `SPU Decoder=Recompiler (LLVM)`, `Use LLVM CPU=cortex-a78`, native scheduler enabled when affinity succeeds, and on-disk shader cache enabled.
- Do not reintroduce an Android startup override that writes `Use LLVM CPU = cortex-a34`; that silently downgrades Thor JIT codegen and can undo the profile on later launches. Do not let this vendored LLVM use `cortex-a510`, `cortex-a710`, `cortex-a715`, or `cortex-x3` on Thor unless Android reports SVE: those Armv9 CPU definitions enable SVE/SVE2 in LLVM while Snapdragon 8 Gen 2 reports dotprod/i8mm/bf16 but not SVE.
- Do not allow Thor per-game recommended configs to select `SPU Decoder: Recompiler (ASMJIT)`. ASMJIT is an x64 SPU backend; ARM64 should use the LLVM SPU recompiler.
- Native wrapper affinity helper: `RPCSX.setProcessAffinityMask(0xF8)` pins current app/native threads to Thor CPUs `3-7` where Android permits it. MainActivity applies it before core initialization so early native threads can inherit the performance-core mask. This is a first-pass compile relief, not a replacement for native PPU/SPU/RSX per-class affinity.
- The first `Thor Feature Doctor` slice lives in `_rpcsx_systemInfo()` inside `app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp`; the existing System Info dialog now reports configured LLVM CPU, fallback CPU, AArch64 per-core names, and Android HWCAP/HWCAP2 feature flags.
- Custom GPU driver UI lives at `app/src/main/java/net/rpcsx/ui/drivers/GpuDriversScreen.kt`.
- Curated GPU driver channels live in `app/src/main/java/net/rpcsx/ui/channels/UpdateChannelsScreen.kt`; keep K11MCH1/Kimchi first, StevenMXZ experimental, and CI builds clearly marked risky.
- Thor Base/Pro/Max are Adreno 740. Prefer A6xx/A7xx Turnip packages when testing custom drivers, keep `Default` as fallback, and label A8xx/Gen8 packages as not for Thor.
- On-screen controls preference lives in `ControllerOverlayPrefs`; Thor/AYN/kalama targets default hidden, non-Thor devices default visible.
- Sixaxis motion preference lives in `SixaxisMotionPrefs`, Android sensor capture lives in `SixaxisMotionController`, and the JNI wrapper looks for `_rpcsx_overlayPadMotionData`. Current downloaded cores may not export it; keep the matching core patch in `core-patches/` until core builds include it.
- Next low-risk Android work: cache cheat badge lookups per game title ID, add stale-cache/core-version labeling, and keep heavy global cheat expansion off Base unless requested.
- Next native/core work: benchmark/iterate the Thor PPU/SPU/RSX affinity split, add authoritative cache status, and continue SPU common recompiler parity. UI-only changes cannot truly pin native compile threads.
- Default PPU compile experiment for Base/Pro/Max: Max LLVM compile threads `2`, full PPU precompile off, heavy mask `0xF8`; benchmark higher worker counts only as an opt-in cache-builder experiment with memory logs running.

## Current Cheat/Test Fixture

- Odin Sphere Leifthrasir BLUS31601 has a conversion fixture.
- Fixture source: `app/src/main/assets/cheats/ncl/1417_Odin Sphere Leifthrasir BLUS31601 v01.01 av01.00.ncl`
- Converted output:
  - `app/src/test/resources/cheats/converted/odin_sphere_leifthrasir_blus31601_patch.yml`
  - `app/src/test/resources/cheats/converted/odin_sphere_leifthrasir_blus31601_patch_config.yml`
