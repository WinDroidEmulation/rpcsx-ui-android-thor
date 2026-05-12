package net.rpcsx.config

import android.content.Context
import android.util.Log
import net.rpcsx.Game
import net.rpcsx.RPCSX
import net.rpcsx.utils.GameIdentity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object GameSettingsDatabase {
    private const val TAG = "GameSettingsDatabase"
    private const val ASSET_PATH = "config/config_database.dat"
    private const val PREFS_NAME = "rpcsx_auto_game_settings"
    private const val DISABLED_PREFIX = "disabled_"
    private const val MANAGED_HEADER = "# RPCSX_THOR_AUTO_SETTINGS"
    private const val TIMESTAMP_HEADER = "# Database timestamp: "
    private const val SOURCE_URL = "https://api.rpcs3.net/config/?api=v1"

    private val lock = Any()
    private var cachedDatabase: Database? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val thorProfileOverrides = mapOf(
        "BLUS30161" to """
            # RPCSX_THOR_PROFILE_OVERRIDE
            # Eternal Sonata stability profile for AYN Thor.
            # Community note: 30 FPS avoids common battle/menu crash paths.
            # SPURS/thread/audio workarounds are excluded on Thor after local PPU crash testing.
            Video:
              Frame limit: 30
              Write Color Buffers: true
        """.trimIndent()
    )

    data class Status(
        val titleId: String?,
        val hasProfile: Boolean,
        val enabled: Boolean,
        val applied: Boolean,
        val customConfigPresent: Boolean,
        val configPath: String?,
        val databaseTimestamp: Long?,
        val databaseSource: String?,
        val databaseProfileCount: Int?,
        val cachePath: String?,
        val managedConfigStale: Boolean = false,
        val error: String? = null
    )

    data class RefreshResult(
        val updated: Boolean,
        val timestamp: Long?,
        val profileCount: Int?,
        val message: String?
    )

    private enum class DatabaseSource(val label: String) {
        LocalCache("local cache"),
        BundledSnapshot("bundled snapshot")
    }

    private data class Database(
        val timestamp: Long,
        val profiles: Map<String, String>,
        val source: DatabaseSource,
        val cachePath: String?
    )

    fun ensureDatabaseExported(context: Context): Boolean {
        return runCatching {
            val bundledText = readBundledDatabaseText(context)
            val bundled = parseDatabase(
                json = bundledText,
                source = DatabaseSource.BundledSnapshot,
                cachePath = null
            )
            val local = readLocalDatabase(context)

            if (local == null || local.timestamp < bundled.timestamp) {
                val target = localDatabaseFile(context)
                target.parentFile?.mkdirs()
                target.writeText(bundledText)
                synchronized(lock) { cachedDatabase = null }
            }

            true
        }.getOrElse {
            Log.w(TAG, "Could not prepare local config database cache", it)
            false
        }
    }

    fun refreshLocalCache(context: Context): RefreshResult {
        return runCatching {
            val before = loadDatabase(context)
            val request = Request.Builder().url(SOURCE_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return RefreshResult(
                        updated = false,
                        timestamp = before?.timestamp,
                        profileCount = before?.profiles?.size,
                        message = "Could not update settings cache: ${response.code}"
                    )
                }

                val body = response.body.string()
                val target = localDatabaseFile(context)
                val remote = parseDatabase(
                    json = body,
                    source = DatabaseSource.LocalCache,
                    cachePath = target.absolutePath
                )

                if (before != null && before.source == DatabaseSource.LocalCache && before.timestamp > remote.timestamp) {
                    return RefreshResult(
                        updated = false,
                        timestamp = before.timestamp,
                        profileCount = before.profiles.size,
                        message = "Local settings cache is newer than the server copy."
                    )
                }

                target.parentFile?.mkdirs()
                target.writeText(body)
                synchronized(lock) { cachedDatabase = remote }

                val beforeProfileCount = before?.profiles?.size
                val changed = before?.timestamp != remote.timestamp ||
                    beforeProfileCount != remote.profiles.size
                RefreshResult(
                    updated = changed,
                    timestamp = remote.timestamp,
                    profileCount = remote.profiles.size,
                    message = if (changed) {
                        "Updated settings cache."
                    } else {
                        "Settings cache is already current."
                    }
                )
            }
        }.getOrElse {
            Log.w(TAG, "Could not refresh local config database cache", it)
            val current = loadDatabase(context)
            RefreshResult(
                updated = false,
                timestamp = current?.timestamp,
                profileCount = current?.profiles?.size,
                message = "Could not update settings cache: ${it.message ?: "network error"}"
            )
        }
    }

    fun statusForGame(context: Context, game: Game): Status {
        val database = loadDatabase(context)
        val titleId = GameIdentity.primaryTitleId(game)
        if (titleId == null) {
            return Status(
                titleId = null,
                hasProfile = false,
                enabled = false,
                applied = false,
                customConfigPresent = false,
                configPath = null,
                databaseTimestamp = database?.timestamp,
                databaseSource = database?.source?.label,
                databaseProfileCount = database?.profiles?.size,
                cachePath = database?.cachePath
            )
        }

        val profileConfig = database?.profiles?.get(titleId)
        val hasProfile = profileConfig != null
        val target = customConfigFile(titleId)
        val disabled = isDisabled(context, titleId)
        val configText = target?.takeIf { it.exists() }?.readText()
        val managed = configText?.startsWith(MANAGED_HEADER) == true
        val managedTimestamp = managedConfigTimestamp(configText)
        val expectedManagedConfig = if (database?.timestamp != null && profileConfig != null) {
            buildManagedConfig(titleId, database.timestamp, profileConfig)
        } else {
            null
        }
        val timestampStale = database?.timestamp != null &&
            managedTimestamp != null &&
            managedTimestamp != database.timestamp
        val contentStale = expectedManagedConfig != null &&
            configText != expectedManagedConfig
        val managedStale = managed && (timestampStale || contentStale)
        val custom = configText != null && !managed

        return Status(
            titleId = titleId,
            hasProfile = hasProfile,
            enabled = hasProfile && !disabled && !custom,
            applied = hasProfile && managed && !managedStale,
            customConfigPresent = custom,
            configPath = target?.absolutePath,
            databaseTimestamp = database?.timestamp,
            databaseSource = database?.source?.label,
            databaseProfileCount = database?.profiles?.size,
            cachePath = database?.cachePath,
            managedConfigStale = managedStale
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
        return applyRecommendedConfig(context, game, replaceCustomConfig = false)
    }

    fun replaceCustomWithRecommendedConfig(context: Context, game: Game): Status {
        return applyRecommendedConfig(context, game, replaceCustomConfig = true)
    }

    private fun applyRecommendedConfig(
        context: Context,
        game: Game,
        replaceCustomConfig: Boolean
    ): Status {
        val titleId = GameIdentity.primaryTitleId(game) ?: return statusForGame(context, game)
        val database = loadDatabase(context) ?: return statusForGame(context, game).copy(
            error = "Settings cache could not be loaded"
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
                if (!replaceCustomConfig) {
                    statusForGame(context, game)
                } else {
                    backupCustomConfig(target)
                    val body = buildManagedConfig(titleId, database.timestamp, config)
                    target.writeText(body)
                    prefs(context)
                        .edit()
                        .putBoolean(DISABLED_PREFIX + titleId, false)
                        .apply()
                    statusForGame(context, game)
                }
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

    private fun backupCustomConfig(target: File) {
        if (!target.exists()) {
            return
        }

        val backupName = buildString {
            append(target.nameWithoutExtension)
            append(".user-backup-")
            append(System.currentTimeMillis())
            if (target.extension.isNotBlank()) {
                append('.')
                append(target.extension)
            }
        }
        target.copyTo(File(target.parentFile, backupName), overwrite = false)
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

    private fun loadDatabase(context: Context): Database? = synchronized(lock) {
        cachedDatabase?.let { return@synchronized it }

        val database = readLocalDatabase(context) ?: readBundledDatabase(context)
        cachedDatabase = database
        database
    }

    private fun readLocalDatabase(context: Context): Database? {
        val file = localDatabaseFile(context)
        if (!file.exists()) {
            return null
        }

        return runCatching {
            parseDatabase(
                json = file.readText(),
                source = DatabaseSource.LocalCache,
                cachePath = file.absolutePath
            )
        }.getOrElse {
            Log.w(TAG, "Ignoring invalid local config database cache", it)
            null
        }
    }

    private fun readBundledDatabase(context: Context): Database? {
        return runCatching {
            parseDatabase(
                json = readBundledDatabaseText(context),
                source = DatabaseSource.BundledSnapshot,
                cachePath = null
            )
        }.getOrElse {
            Log.w(TAG, "Could not load bundled config database", it)
            null
        }
    }

    private fun readBundledDatabaseText(context: Context): String =
        context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }

    private fun parseDatabase(
        json: String,
        source: DatabaseSource,
        cachePath: String?
    ): Database {
        val root = JSONObject(json)
        if (root.optInt("return_code", -1) < 0) {
            error("Config database returned an error code")
        }

        val games = root.optJSONObject("games") ?: error("Config database has no games object")
        val profiles = buildMap {
            val keys = games.keys()
            while (keys.hasNext()) {
                val titleId = keys.next()
                val config = games.optJSONObject(titleId)?.optString("config").orEmpty()
                if (config.isNotBlank()) {
                    put(titleId, config)
                }
            }
            thorProfileOverrides.forEach { (titleId, config) ->
                put(titleId, config)
            }
        }

        if (profiles.isEmpty()) {
            error("Config database has no valid profiles")
        }

        return Database(
            timestamp = root.optLong("timestamp", 0L),
            profiles = profiles,
            source = source,
            cachePath = cachePath
        )
    }

    private fun localDatabaseFile(context: Context): File {
        val externalRoot = context.getExternalFilesDir(null)
        val root = when {
            RPCSX.rootDirectory.isNotBlank() -> File(RPCSX.rootDirectory)
            externalRoot != null -> externalRoot
            else -> context.filesDir
        }

        return File(root, "config/GuiConfigs/config_database.dat")
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
            appendLine(TIMESTAMP_HEADER + timestamp)
            appendLine("# Title ID: $titleId")
            append(config.trimEnd())
            appendLine()
        }
    }

    private fun managedConfigTimestamp(configText: String?): Long? {
        if (configText.isNullOrBlank()) {
            return null
        }

        return configText
            .lineSequence()
            .firstOrNull { it.startsWith(TIMESTAMP_HEADER) }
            ?.removePrefix(TIMESTAMP_HEADER)
            ?.trim()
            ?.toLongOrNull()
    }

    private fun isDisabled(context: Context, titleId: String): Boolean =
        prefs(context).getBoolean(DISABLED_PREFIX + titleId, false)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
