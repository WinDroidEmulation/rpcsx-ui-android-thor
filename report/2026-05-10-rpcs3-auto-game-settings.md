# 2026-05-10 RPCS3 Automatic Game Settings Notes

## What Exists Upstream

RPCS3 now has a config database flow that is much better than asking users to copy Wiki settings by hand.

The current upstream implementation uses `https://api.rpcs3.net/config/?api=v1`, not a live Wiki scrape in the emulator UI. RPCS3 packaging scripts download that endpoint into `bin/GuiConfigs/config_database.dat`. The Qt-side `config_database` reader loads `config_database.dat`, checks `return_code`, reads `games`, and validates each `games[TITLEID].config` string as a normal RPCS3 YAML config snippet.

At boot, RPCS3 retrieves a title-ID match and applies the database config on top of the active config path. If a custom per-game config exists, upstream intentionally leaves the custom config alone and ignores the database config for that game.

Primary upstream references:

- RPCS3 CI packaging downloads `config_database.dat`: <https://github.com/RPCS3/rpcs3/blob/cd7cb1cc11fdcae484fc6b2d447be96e045fed1b/.ci/deploy-windows.sh#L15-L17>
- RPCS3 config database reader/downloader: <https://github.com/RPCS3/rpcs3/blob/cd7cb1cc11fdcae484fc6b2d447be96e045fed1b/rpcs3/rpcs3qt/config_database.cpp#L12-L92>
- RPCS3 JSON/YAML validation path: <https://github.com/RPCS3/rpcs3/blob/cd7cb1cc11fdcae484fc6b2d447be96e045fed1b/rpcs3/rpcs3qt/config_database.cpp#L139-L224>
- RPCS3 boot-time merge and custom-config guard: <https://github.com/RPCS3/rpcs3/blob/cd7cb1cc11fdcae484fc6b2d447be96e045fed1b/rpcs3/Emu/System.cpp#L1571-L1657>
- Live config API endpoint: <https://api.rpcs3.net/config/?api=v1>

## Fork Snapshot

Bundled file: `app/src/main/assets/config/config_database.dat`

Snapshot stats:

| Field | Value |
| --- | --- |
| Source | `https://api.rpcs3.net/config/?api=v1` |
| API timestamp UTC | `2026-05-02 17:53:44` |
| Game profiles | `2125` |
| File size | `292581` bytes |

## Android Integration

This fork now has an Android-side manager at `app/src/main/java/net/rpcsx/config/GameSettingsDatabase.kt`.

The manager does four practical things:

1. Loads the bundled RPCS3 config database from APK assets.
2. Exports the same snapshot to `config/GuiConfigs/config_database.dat` under the RPCSX root, matching the upstream desktop filename/location pattern.
3. Shows a plain `Recommended Settings` switch on the game detail screen when a title ID matches the database.
4. Writes a managed per-game config to `config/custom_configs/config_TITLEID.yml` before launch.

Managed files start with:

```yaml
# RPCSX_THOR_AUTO_SETTINGS
```

That header matters. If a user already has a custom config without that header, the fork does not overwrite it. User-created game configs win, just like upstream RPCS3 treats custom configs as higher priority than database configs.

## UX Rule

Do not expose this to normal users as "database config." The user-facing concept is:

```text
Recommended Settings
```

The switch defaults on for games with a matching title ID. Turning it off removes only this fork's managed config file and records a per-game opt-out.

## Remaining Risk

The Android UI can create the same custom config file shape RPCS3 expects, but the final effect still depends on the loaded RPCSX core honoring `config/custom_configs/config_TITLEID.yml` for Android boots. If the current native library does not load per-game custom configs in Android mode, the next core/API task is to add a direct boot mode or native export for database/custom config selection.

This is still the right first step because it gives the APK a bundled database, a simple game-level toggle, and a safe no-overwrite policy before deeper core changes.
