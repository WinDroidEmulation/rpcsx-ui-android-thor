package net.rpcsx.performance

import android.content.Context
import net.rpcsx.Game
import net.rpcsx.GameProgress
import net.rpcsx.GameProgressType
import net.rpcsx.ProgressRepository
import net.rpcsx.R
import net.rpcsx.RPCSX
import net.rpcsx.config.GameSettingsDatabase
import net.rpcsx.utils.GameIdentity
import java.io.File
import kotlin.concurrent.thread

object GameCacheRepository {
    data class CacheStatus(
        val titleId: String?,
        val cacheDir: String?,
        val exists: Boolean,
        val bytes: Long,
        val ppuEntries: Int,
        val spuEntries: Int,
        val shaderEntries: Int,
        val totalEntries: Int,
        val updatedAtMillis: Long?,
        val prepareSupported: Boolean
    ) {
        val isWarm: Boolean
            get() = exists && bytes > 0 && (ppuEntries > 0 || spuEntries > 0 || shaderEntries > 0)
    }

    fun statusForGame(game: Game): CacheStatus {
        val titleId = GameIdentity.primaryTitleId(game)
        val cacheDir = titleId?.let { gameCacheDir(it) }
        val prepareSupported = runCatching { RPCSX.instance.supportsPpuCachePreparation() }
            .getOrDefault(false)

        if (cacheDir == null || !cacheDir.exists()) {
            return CacheStatus(
                titleId = titleId,
                cacheDir = cacheDir?.absolutePath,
                exists = false,
                bytes = 0L,
                ppuEntries = 0,
                spuEntries = 0,
                shaderEntries = 0,
                totalEntries = 0,
                updatedAtMillis = null,
                prepareSupported = prepareSupported
            )
        }

        var bytes = 0L
        var ppuEntries = 0
        var spuEntries = 0
        var shaderEntries = 0
        var totalEntries = 0
        var updatedAt = cacheDir.lastModified().takeIf { it > 0 }

        cacheDir.walkTopDown().forEach { file ->
            if (file == cacheDir) {
                return@forEach
            }

            totalEntries++
            if (file.name.startsWith("ppu-", ignoreCase = true)) {
                ppuEntries++
            }
            if (file.name.startsWith("spu-", ignoreCase = true)) {
                spuEntries++
            }
            if (file.isFile && file.isInsideDirectory("shaders_cache")) {
                shaderEntries++
            }
            if (file.isFile) {
                bytes += file.length()
            }
            if (file.lastModified() > (updatedAt ?: 0L)) {
                updatedAt = file.lastModified()
            }
        }

        return CacheStatus(
            titleId = titleId,
            cacheDir = cacheDir.absolutePath,
            exists = true,
            bytes = bytes,
            ppuEntries = ppuEntries,
            spuEntries = spuEntries,
            shaderEntries = shaderEntries,
            totalEntries = totalEntries,
            updatedAtMillis = updatedAt,
            prepareSupported = prepareSupported
        )
    }

    fun clearGameCache(game: Game): CacheStatus {
        val titleId = GameIdentity.primaryTitleId(game)
        if (titleId != null) {
            gameCacheDir(titleId).deleteRecursively()
        }
        return statusForGame(game)
    }

    fun prepareGameCache(context: Context, game: Game, onFinished: (CacheStatus) -> Unit) {
        if (game.findProgress(GameProgressType.Compile) != null) {
            return
        }

        val titleId = GameIdentity.primaryTitleId(game).orEmpty()
        if (titleId.isBlank() || !RPCSX.instance.supportsPpuCachePreparation()) {
            ProgressRepository.create(context, context.getString(R.string.cache_preparation)) { entry ->
                if (entry.isFinished()) {
                    onFinished(statusForGame(game))
                }
            }.also { progressId ->
                ProgressRepository.onProgressEvent(
                    progressId,
                    -1,
                    0,
                    "This RPCSX core cannot prepare compiled cache in the background yet. Start the game once to build CPU cache during boot and shader cache while playing."
                )
            }
            return
        }

        val progressId = ProgressRepository.create(
            context,
            context.getString(R.string.cache_preparation)
        ) { entry ->
            if (entry.isFinished()) {
                game.removeProgress(GameProgressType.Compile)
                onFinished(statusForGame(game))
            }
        }
        game.addProgress(GameProgress(progressId, GameProgressType.Compile))

        thread(name = "RPCSX-PrepareCache", isDaemon = true) {
            ThorPerformanceProfile.applyRuntimeAffinity()
            GameSettingsDatabase.applyRecommendedConfig(context, game)

            val ok = runCatching {
                RPCSX.instance.preparePpuCache(game.info.path, titleId, progressId)
            }.getOrDefault(false)

            if (!ok) {
                ProgressRepository.onProgressEvent(
                    progressId,
                    -1,
                    0,
                    "Cache preparation failed or is not supported by this RPCSX core."
                )
            } else {
                ProgressRepository.onProgressEvent(progressId, 1, 1)
            }
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) {
            return "0 MB"
        }

        val mib = bytes / (1024.0 * 1024.0)
        return if (mib < 1024.0) {
            "%.1f MB".format(mib)
        } else {
            "%.2f GB".format(mib / 1024.0)
        }
    }

    private fun gameCacheDir(titleId: String): File =
        File(RPCSX.rootDirectory, "cache/cache/$titleId")

    private fun File.isInsideDirectory(directoryName: String): Boolean {
        var parent = parentFile
        while (parent != null) {
            if (parent.name.equals(directoryName, ignoreCase = true)) {
                return true
            }
            parent = parent.parentFile
        }
        return false
    }
}
