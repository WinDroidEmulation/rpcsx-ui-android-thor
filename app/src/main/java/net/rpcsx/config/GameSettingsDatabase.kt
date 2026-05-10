package net.rpcsx.config

import android.content.Context
import android.util.Log
import net.rpcsx.Game
import net.rpcsx.RPCSX
import net.rpcsx.utils.GameIdentity
import org.json.JSONObject
import java.io.File

object GameSettingsDatabase {
    private const val TAG = "GameSettingsDatabase"
    private const val ASSET_PATH = "config/config_database.dat"
    private const val PREFS_NAME = "rpcsx_auto_game_settings"
    private const val DISABLED_PREFIX = "disabled_"
    private const val MANAGED_HEADER = "# RPCSX_THOR_AUTO_SETTINGS"
    private const val SOURCE_URL = "https://api.rpcs3.net/config/?api=v1"

    private val lock = Any()
    private var cachedDatabase: Database? = null

    data class Status(
        val titleId: String?,
        val hasProfile: Boolean,
        val enabled: Boolean,
        val applied: Boolean,
        val customConfigPresent: Boolean,
        val configPath: String?,
        val databaseTimestamp: Long?,
        val error: String? = null
    )

    private data class Database(
        val timestamp: Long,
        val profiles: Map<String, String>
    )

    fun ensureDatabaseExported(context: Context): Boolean {
        return runCatching {
            if (RPCSX.rootDirectory.isBlank()) {
                false
            } else {
                val target = File(RPCSX.rootDirectory, "config/GuiConfigs/config_database.dat")
                val bytes = context.assets.open(ASSET_PATH).use { it.readBytes() }

                if (!target.exists() || !target.readBytes().contentEquals(bytes)) {
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                }

                true
            }
        }.getOrElse {
            Log.w(TAG, "Could not export bundled config database", it)
            false
        }
    }

    fun statusForGame(context: Context, game: Game): Status {
        val titleId = GameIdentity.primaryTitleId(game)
        if (titleId == null) {
            return Status(
                titleId = null,
                hasProfile = false,
                enabled = false,
                applied = false,
                customConfigPresent = false,
                configPath = null,
                databaseTimestamp = databaseTimestamp(context)
            )
        }

        val database = loadDatabase(context)
        val hasProfile = database?.profiles?.containsKey(titleId) == true
        val target = customConfigFile(titleId)
        val disabled = isDisabled(context, titleId)
        val configText = target?.takeIf { it.exists() }?.readText()
        val managed = configText?.startsWith(MANAGED_HEADER) == true
        val custom = configText != null && !managed

        return Status(
            titleId = titleId,
            hasProfile = hasProfile,
            enabled = hasProfile && !disabled && !custom,
            applied = hasProfile && managed,
            customConfigPresent = custom,
            configPath = target?.absolutePath,
            databaseTimestamp = database?.timestamp
        )
    }

    fun setRecommendedSettingsEnabled(context: Context, game: Game, enabled: Boolean): Status {
        val titleId = GameIdentity.primaryTitleId(game) ?: return statusForGame(context, game)

        prefs(context)
            .edit()
            .putBoolean(DISABLED_PREFIX + titleId, !enabled)
            .apply()

        if (enabled) {
            applyRecommendedConfig(context, game)
        } else {
            removeManagedConfig(titleId)
        }

        return statusForGame(context, game)
    }

    fun applyRecommendedConfig(context: Context, game: Game): Status {
        val titleId = GameIdentity.primaryTitleId(game) ?: return statusForGame(context, game)
        val database = loadDatabase(context) ?: return statusForGame(context, game).copy(
            error = "Bundled settings database could not be loaded"
        )
        val config = database.profiles[titleId] ?: return statusForGame(context, game)

        if (isDisabled(context, titleId)) {
            return statusForGame(context, game)
        }

        val target = customConfigFile(titleId) ?: return statusForGame(context, game).copy(
            error = "RPCSX root directory is not ready"
        )

        return runCatching {
            val existing = target.takeIf { it.exists() }?.readText()
            if (existing != null && !existing.startsWith(MANAGED_HEADER)) {
                statusForGame(context, game)
            } else {
                val body = buildManagedConfig(titleId, database.timestamp, config)
                if (existing != body) {
                    target.parentFile?.mkdirs()
                    target.writeText(body)
                }

                statusForGame(context, game)
            }
        }.getOrElse {
            Log.w(TAG, "Could not apply recommended settings for $titleId", it)
            statusForGame(context, game).copy(error = it.message)
        }
    }

    private fun removeManagedConfig(titleId: String) {
        val target = customConfigFile(titleId) ?: return
        if (!target.exists()) {
            return
        }

        val existing = runCatching { target.readText() }.getOrNull()
        if (existing?.startsWith(MANAGED_HEADER) == true) {
            target.delete()
        }
    }

    private fun databaseTimestamp(context: Context): Long? = loadDatabase(context)?.timestamp

    private fun loadDatabase(context: Context): Database? = synchronized(lock) {
        cachedDatabase?.let { return@synchronized it }

        val database = runCatching {
            val json = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            if (root.optInt("return_code", -1) < 0) {
                error("Config database returned an error code")
            }

            val games = root.getJSONObject("games")
            val profiles = buildMap {
                val keys = games.keys()
                while (keys.hasNext()) {
                    val titleId = keys.next()
                    val config = games.optJSONObject(titleId)?.optString("config").orEmpty()
                    if (config.isNotBlank()) {
                        put(titleId, config)
                    }
                }
            }

            Database(
                timestamp = root.optLong("timestamp", 0L),
                profiles = profiles
            )
        }.getOrElse {
            Log.w(TAG, "Could not load bundled config database", it)
            null
        }

        cachedDatabase = database
        database
    }

    private fun customConfigFile(titleId: String): File? {
        if (RPCSX.rootDirectory.isBlank()) {
            return null
        }

        return File(RPCSX.rootDirectory, "config/custom_configs/config_$titleId.yml")
    }

    private fun buildManagedConfig(titleId: String, timestamp: Long, config: String): String {
        return buildString {
            appendLine(MANAGED_HEADER)
            appendLine("# Source: $SOURCE_URL")
            appendLine("# Database timestamp: $timestamp")
            appendLine("# Title ID: $titleId")
            append(config.trimEnd())
            appendLine()
        }
    }

    private fun isDisabled(context: Context, titleId: String): Boolean =
        prefs(context).getBoolean(DISABLED_PREFIX + titleId, false)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
