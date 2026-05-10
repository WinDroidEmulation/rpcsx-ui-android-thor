# RPCSX for AYN Thor Experiment

![RPCSX for AYN Thor Experiment](docs/images/rpcsx-thor-experiment-banner.png)

This is a personal-use Android fork of RPCSX-UI-Android aimed at the AYN Thor, especially Snapdragon 8 Gen 2 units. It is an experiment, it is vibe-coded with AI assistance, and it will move fast in whatever direction makes this handheld easier to use.

No stability guarantee. No support guarantee. Do not open issues expecting upstream-style triage. If the experiment annoys you, fork it and make your own version.

## What This Fork Is

- A Thor-first Android UI experiment for RPCSX.
- Built around simple library use, external storage game discovery, covers, cheat visibility, and trimming experiments.
- Tuned around the real connected target device: `AYN Thor`, board/platform `kalama`, Android package `net.rpcsx.easy`.
- Still GPLv2, still based on the upstream Android UI, and still dependent on the RPCSX core behavior underneath it.

## What This Fork Is Not

- Not an official RPCSX project.
- Not an AYN project.
- Not a promise that every PS3 game will boot, run fast, or survive every update.
- Not a place to ask for games, firmware, system files, keys, or piracy help.

Use legally owned dumps and legally obtained firmware. Personal use only.

## Screenshots

Captured from the connected AYN Thor test device.

![Thor library](docs/screenshots/rpcsx-thor-library.png)

![Thor menu](docs/screenshots/rpcsx-thor-menu.png)

![Overlay editor](docs/screenshots/rpcsx-thor-overlay-editor.png)

The requested Star Fox shot is not included because the connected PS3 library did not contain a Star Fox entry during capture. I am not fabricating unrelated IP screenshots for the README.

## Where This Diverges

- Rebranded as `RPCSX for AYN Thor Experiment`.
- Automatic upstream UI/core update prompts are disabled for this fork through `BuildConfig.FORK_BUILD=true`.
- External ISO folder import is handled as direct library entries instead of blindly extracting loose ISO contents.
- ISO metadata and cover lookup read `PS3_GAME/PARAM.SFO` and `PS3_GAME/ICON0.PNG` directly where possible.
- Cheat work is now a first-class fork feature: bundled cheat database assets, cheat badges, per-game cheat visibility, Artemis/Aldos import experiments, and RPCS3 patch imports.
- Trim/Optimize is intentionally visible as an experimental tool path rather than hidden developer plumbing.
- Thor-specific performance research lives under `report/`, including PPU compile/cache notes and Snapdragon 8 Gen 2 targeting.
- Generated icon and README art are custom for this fork and intentionally avoid console/game/IP logos.

## AYN Thor Target Notes

The connected Thor reports `kalama` hardware and this CPU layout from `/proc/cpuinfo`:

| CPU | Part | Interpreted core |
| ---: | --- | --- |
| 0 | `0xd46` | Cortex-A510 |
| 1 | `0xd46` | Cortex-A510 |
| 2 | `0xd46` | Cortex-A510 |
| 3 | `0xd4d` | Cortex-A715 |
| 4 | `0xd4d` | Cortex-A715 |
| 5 | `0xd47` | Cortex-A710 |
| 6 | `0xd47` | Cortex-A710 |
| 7 | `0xd4e` | Cortex-X3 |

Useful masks for future native/core work:

| Group | CPUs | Mask |
| --- | --- | --- |
| Efficiency only | `0-2` | `0x07` |
| A715 only | `3-4` | `0x18` |
| A710 only | `5-6` | `0x60` |
| Prime X3 only | `7` | `0x80` |
| Performance plus prime | `3-7` | `0xF8` |
| A715 plus prime | `3-4,7` | `0x98` |

The practical performance direction is boring but important: keep PPU/SPU/shader caches on internal storage, cap LLVM compile workers before they heat-soak the handheld, detect actual CPU topology, and expose one obvious Thor preset instead of making people decode advanced settings.

## Reports

- [APS3E, RPCSX, and Thor PPU compile notes](report/2026-05-10-aps3e-rpcsx-thor-ppu-compile.md)
- [Snapdragon 8 Gen 2 Thor target notes](report/2026-05-10-snapdragon-8-gen-2-thor-target.md)

## Building

Requirements:

- Android 10+
- JDK 17
- Android SDK

Debug APK output:

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected debug APK name:

```text
app\build\outputs\apk\debug\rpcsx-thor-experiment-debug.apk
```

## License

This fork keeps the upstream GPLv2 license unless a directory or file contains its own license.
