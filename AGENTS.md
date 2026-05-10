# RPCSX for AYN Thor Experiment Agent Notes

## Repo And Git

- Work on `master` unless the user explicitly asks for a branch.
- Remote push target is SSH: `git@github.com:noeldvictor/rpcsx-ui-android-thor.git`.
- Commit and push completed work to `origin master`.
- Do not fork extra RPCSX repos for this project; keep Android-side work in this repo unless the user asks otherwise.
- Public positioning: this is a personal-use, AI-assisted/vibe-coded AYN Thor experiment. Do not present it as official RPCSX, official AYN, stable, or support-backed.

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
```

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
- The app exports the bundled snapshot to `config/GuiConfigs/config_database.dat` under the RPCSX root so a future/core-side config database reader can find the familiar upstream location.
- The game detail screen shows this as `Recommended Settings`, not as database jargon.
- Managed per-game configs are written to `config/custom_configs/config_TITLEID.yml` with the `# RPCSX_THOR_AUTO_SETTINGS` header.
- Never overwrite an existing custom config unless it has the managed header. User-created custom configs win.
- Boot flow applies the recommended config before launch only when the game has a title-ID match and the user has not turned the switch off for that game.

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
- Next low-risk Android work: cache cheat badge lookups per game title ID, add per-game cache state, and keep heavy global cheat expansion off Base unless requested.
- Next native/core work: expose PPU cache precompile, CPU topology, PPU/SPU/RSX affinity masks, and cache status. UI-only changes cannot truly pin native compile threads.
- Default PPU compile experiment for Base/Pro/Max: Max LLVM compile threads `4`, heavy mask `0xF8`, then benchmark `3`, `5`, and `6`.

## Current Cheat/Test Fixture

- Odin Sphere Leifthrasir BLUS31601 has a conversion fixture.
- Fixture source: `app/src/main/assets/cheats/ncl/1417_Odin Sphere Leifthrasir BLUS31601 v01.01 av01.00.ncl`
- Converted output:
  - `app/src/test/resources/cheats/converted/odin_sphere_leifthrasir_blus31601_patch.yml`
  - `app/src/test/resources/cheats/converted/odin_sphere_leifthrasir_blus31601_patch_config.yml`
