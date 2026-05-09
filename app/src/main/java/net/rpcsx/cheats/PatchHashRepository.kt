package net.rpcsx.cheats

import android.content.Context
import androidx.core.content.edit
import net.rpcsx.Game
import net.rpcsx.RPCSX
import net.rpcsx.utils.GameIdentity
import java.io.File
import java.util.Locale

enum class CheatPatchStatus {
    NoTitleId,
    NeedsFirstBoot,
    HashLearned,
    Installed
}

data class PatchHashStatus(
    val titleId: String?,
    val ppuHash: String?,
    val spuHashes: List<String>,
    val patchFilePath: String?,
    val configFilePath: String?,
    val status: CheatPatchStatus
)

object PatchHashRepository {
    private const val PREFS = "patch_hashes"
    private const val PATCH_SECTION_START = "# RPCSX Easy Artemis patches start"
    private const val PATCH_SECTION_END = "# RPCSX Easy Artemis patches end"
    private const val CONFIG_SECTION_START = "# RPCSX Easy Artemis patch config start"
    private const val CONFIG_SECTION_END = "# RPCSX Easy Artemis patch config end"

    private val titleIdRegex = Regex("\\b[A-Z]{4}\\d{5}\\b", RegexOption.IGNORE_CASE)
    private val ppuHashRegex = Regex("PPU executable hash:\\s*([A-Za-z0-9_-]{8,96})")
    private val spuHashRegex = Regex("SPU executable hash:\\s*([A-Za-z0-9_-]{8,96})")

    fun cachedStatus(context: Context, game: Game): PatchHashStatus {
        val titleId = GameIdentity.primaryTitleId(game)
        return buildStatus(context, titleId, cachedPpuHash(context, game, titleId), cachedSpuHashes(context, game, titleId))
    }

    fun learnFromLogs(context: Context, game: Game): PatchHashStatus {
        val titleId = GameIdentity.primaryTitleId(game)
        if (titleId == null) {
            return buildStatus(context, null, null, emptyList())
        }

        val learned = parseLogs(context, titleId)
        if (learned.ppuHash != null && isLikelyCurrentGame(game)) {
            rememberPpuHash(context, game, titleId, learned.ppuHash)
        } else if (learned.ppuHashForTitle != null) {
            rememberPpuHash(context, game, titleId, learned.ppuHashForTitle)
        }

        if (learned.spuHashes.isNotEmpty()) {
            rememberSpuHashes(context, game, titleId, learned.spuHashes)
        }

        return buildStatus(
            context = context,
            titleId = titleId,
            ppuHash = cachedPpuHash(context, game, titleId),
            spuHashes = cachedSpuHashes(context, game, titleId)
        )
    }

    fun requirePpuHash(context: Context, game: Game, titleId: String): String? {
        cachedPpuHash(context, game, titleId)?.let { return it }
        return learnFromLogs(context, game).ppuHash
    }

    fun statusText(status: PatchHashStatus): String {
        return when (status.status) {
            CheatPatchStatus.NoTitleId -> "No title ID"
            CheatPatchStatus.NeedsFirstBoot -> "Needs first boot"
            CheatPatchStatus.HashLearned -> "Hash learned"
            CheatPatchStatus.Installed -> "Patches installed"
        }
    }

    private fun buildStatus(
        context: Context,
        titleId: String?,
        ppuHash: String?,
        spuHashes: List<String>
    ): PatchHashStatus {
        val root = rpcsxRoot(context)
        val patchFile = titleId?.let { File(root, "config/patches/${it}_patch.yml") }
        val configFile = File(root, "config/patch_config.yml")
        val installed = patchFile?.hasGeneratedSectionContent(PATCH_SECTION_START, PATCH_SECTION_END) == true &&
            configFile.hasGeneratedSectionContent(CONFIG_SECTION_START, CONFIG_SECTION_END)

        val status = when {
            titleId == null -> CheatPatchStatus.NoTitleId
            installed -> CheatPatchStatus.Installed
            ppuHash != null -> CheatPatchStatus.HashLearned
            else -> CheatPatchStatus.NeedsFirstBoot
        }

        return PatchHashStatus(
            titleId = titleId,
            ppuHash = ppuHash,
            spuHashes = spuHashes,
            patchFilePath = patchFile?.absolutePath,
            configFilePath = configFile.absolutePath,
            status = status
        )
    }

    private fun parseLogs(context: Context, titleId: String): LearnedHashes {
        val root = rpcsxRoot(context)
        val logs = listOf(
            File(root, "cache/RPCSX.old.log"),
            File(root, "cache/RPCSX.log")
        )

        var ppuHashForTitle: String? = null
        var latestPpuHash: String? = null
        val spuHashes = linkedSetOf<String>()

        logs.filter { it.exists() }.forEach { log ->
            val text = log.readTextOrNull() ?: return@forEach
            val parsed = parseLogText(text, titleId)
            ppuHashForTitle = parsed.ppuHashForTitle ?: ppuHashForTitle
            latestPpuHash = parsed.ppuHash ?: latestPpuHash
            spuHashes += parsed.spuHashes
        }

        return LearnedHashes(
            ppuHashForTitle = ppuHashForTitle,
            ppuHash = latestPpuHash,
            spuHashes = spuHashes.toList()
        )
    }

    internal fun parseLogText(text: String, titleId: String): LearnedHashes {
        var activeTitleId: String? = null
        var ppuHashForTitle: String? = null
        var latestPpuHash: String? = null
        val spuHashes = linkedSetOf<String>()
        val normalizedTitleId = titleId.uppercase(Locale.US)

        text.lineSequence().forEach { line ->
            titleIdRegex.find(line)?.let { activeTitleId = it.value.uppercase(Locale.US) }
            ppuHashRegex.find(line)?.let { match ->
                val hash = match.groupValues[1]
                latestPpuHash = hash
                if (activeTitleId == normalizedTitleId || line.contains(titleId, ignoreCase = true)) {
                    ppuHashForTitle = hash
                }
            }
            spuHashRegex.find(line)?.let { match ->
                if (activeTitleId == normalizedTitleId || line.contains(titleId, ignoreCase = true)) {
                    spuHashes += match.groupValues[1]
                }
            }
        }

        return LearnedHashes(
            ppuHashForTitle = ppuHashForTitle,
            ppuHash = latestPpuHash,
            spuHashes = spuHashes.toList()
        )
    }

    private fun isLikelyCurrentGame(game: Game): Boolean {
        return RPCSX.lastPlayedGame == game.info.path || RPCSX.activeGame.value == game.info.path
    }

    private fun rememberPpuHash(context: Context, game: Game, titleId: String, hash: String) {
        prefs(context).edit {
            putString(ppuHashPrefKey(game, titleId), hash)
        }
    }

    private fun rememberSpuHashes(context: Context, game: Game, titleId: String, hashes: List<String>) {
        prefs(context).edit {
            putStringSet(spuHashPrefKey(game, titleId), hashes.toSet())
        }
    }

    private fun cachedPpuHash(context: Context, game: Game, titleId: String?): String? {
        if (titleId == null) {
            return null
        }

        return prefs(context).getString(ppuHashPrefKey(game, titleId), null)
    }

    private fun cachedSpuHashes(context: Context, game: Game, titleId: String?): List<String> {
        if (titleId == null) {
            return emptyList()
        }

        return prefs(context).getStringSet(spuHashPrefKey(game, titleId), emptySet()).orEmpty().sorted()
    }

    private fun ppuHashPrefKey(game: Game, titleId: String): String =
        "ppu_hash:${titleId}:${game.info.path}"

    private fun spuHashPrefKey(game: Game, titleId: String): String =
        "spu_hashes:${titleId}:${game.info.path}"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun rpcsxRoot(context: Context): File {
        return if (RPCSX.rootDirectory.isNotBlank()) {
            File(RPCSX.rootDirectory)
        } else {
            context.getExternalFilesDir(null) ?: context.filesDir
        }
    }

    private fun File.readTextOrNull(): String? =
        runCatching { if (exists()) readText() else null }.getOrNull()

    private fun File.hasGeneratedSectionContent(startMarker: String, endMarker: String): Boolean {
        val text = readTextOrNull() ?: return false
        val start = text.indexOf(startMarker)
        val end = text.indexOf(endMarker)
        if (start < 0 || end <= start) {
            return false
        }

        return text.substring(start + startMarker.length, end)
            .lineSequence()
            .map { it.trim() }
            .any { it.isNotBlank() && !it.startsWith("#") }
    }

    data class LearnedHashes(
        val ppuHashForTitle: String?,
        val ppuHash: String?,
        val spuHashes: List<String>
    )
}
