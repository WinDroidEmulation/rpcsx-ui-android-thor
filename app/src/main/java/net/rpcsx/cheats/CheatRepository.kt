package net.rpcsx.cheats

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.rpcsx.Game
import net.rpcsx.RPCSX
import net.rpcsx.utils.GameIdentity
import net.rpcsx.utils.GitHub
import java.io.File
import java.net.URLEncoder

@Serializable
data class CheatEntry(
    val titleIds: List<String>,
    val title: String,
    val version: String,
    val size: String,
    val fileName: String,
    val sourceName: String? = null,
    val assetName: String? = null,
    val convertibleCount: Int? = null,
    val riskyCount: Int? = null
)

object CheatRepository {
    private const val CODELIST_URL = "http://ps3.aldostools.org/codelist.html"
    private const val RAW_CODELIST_BASE =
        "https://raw.githubusercontent.com/aldostools/webMAN-MOD/master/_Projects_/codelists/"
    private const val BUNDLED_INDEX_ASSET = "cheats/aldos_index.json"

    val entries = mutableStateListOf<CheatEntry>()
    val isLoading = mutableStateOf(false)
    val lastError = mutableStateOf<String?>(null)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun load(context: Context, forceRefresh: Boolean = false) {
        if (isLoading.value) {
            return
        }

        isLoading.value = true
        lastError.value = null

        withContext(Dispatchers.IO) {
            try {
                val cache = indexCacheFile(context)
                val bundled = if (!forceRefresh) bundledIndex(context) else null
                val parsed = if (bundled != null) {
                    bundled
                } else if (!forceRefresh && cache.exists()) {
                    json.decodeFromString(ListSerializer(CheatEntry.serializer()), cache.readText())
                } else {
                    when (val response = GitHub.get(CODELIST_URL)) {
                        is GitHub.GetResult.Error -> {
                            throw IllegalStateException("Failed to fetch codelist: ${response.code} ${response.message}")
                        }

                        is GitHub.GetResult.Success -> {
                            parseIndex(response.content).also {
                                cache.parentFile?.mkdirs()
                                cache.writeText(json.encodeToString(ListSerializer(CheatEntry.serializer()), it))
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    entries.clear()
                    entries.addAll(parsed)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    lastError.value = e.message ?: "Unknown cheat database error"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isLoading.value = false
                }
            }
        }
    }

    fun matches(game: Game): List<CheatEntry> {
        val ids = GameIdentity.titleIdsForGame(game)
        val name = game.info.name.value?.lowercase().orEmpty()

        if (entries.isEmpty()) {
            return emptyList()
        }

        val titleIdMatches = if (ids.isNotEmpty()) {
            entries.filter { entry -> entry.titleIds.any { it in ids } }
        } else {
            emptyList()
        }

        if (titleIdMatches.isNotEmpty() || name.isBlank()) {
            return titleIdMatches
        }

        return entries.filter { it.title.lowercase().contains(name) }
    }

    fun search(query: String): List<CheatEntry> {
        val normalized = query.trim()
        if (normalized.isBlank()) {
            return entries
        }

        val needle = normalized.lowercase()
        return entries.filter {
            it.title.lowercase().contains(needle) ||
                it.version.lowercase().contains(needle) ||
                it.titleIds.any { id -> id.lowercase().contains(needle) }
        }
    }

    fun hasCheats(game: Game): Boolean = matches(game).isNotEmpty()

    suspend fun getCheatText(context: Context, entry: CheatEntry): String = withContext(Dispatchers.IO) {
        val cache = cheatCacheFile(context, entry)
        if (cache.exists()) {
            return@withContext cache.readText()
        }

        bundledCheatText(context, entry)?.let {
            return@withContext it
        }

        val url = RAW_CODELIST_BASE + encodePathSegment(entry.sourceName ?: entry.fileName) + ".ncl"
        when (val response = GitHub.get(url)) {
            is GitHub.GetResult.Error -> throw IllegalStateException("Failed to fetch cheat file: ${response.code} ${response.message}")
            is GitHub.GetResult.Success -> {
                cache.parentFile?.mkdirs()
                cache.writeText(response.content)
                response.content
            }
        }
    }

    fun sourceUrl(entry: CheatEntry): String =
        RAW_CODELIST_BASE + encodePathSegment(entry.sourceName ?: entry.fileName) + ".ncl"

    private fun parseIndex(html: String): List<CheatEntry> {
        val rowRegex = Regex("<tr><td>(.*?)<td>(.*?)<td>(.*?)<td>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        return rowRegex.findAll(html).mapNotNull { match ->
            val titleIdCell = cleanHtml(match.groupValues[1])
            val title = cleanHtml(match.groupValues[2])
            val version = cleanHtml(match.groupValues[3])
            val size = cleanHtml(match.groupValues[4])

            if (title.isBlank()) {
                return@mapNotNull null
            }

            val titleIds = GameIdentity.titleIdsFromText(titleIdCell)
            val fileName = buildString {
                append(title)
                if (titleIdCell.isNotBlank()) append(" ").append(titleIdCell)
                if (version.isNotBlank()) append(" ").append(version)
            }

            CheatEntry(
                titleIds = titleIds,
                title = title,
                version = version,
                size = size,
                fileName = fileName
            )
        }.distinctBy { it.fileName }.toList()
    }

    private fun cleanHtml(value: String): String {
        return value
            .replace(Regex("<.*?>"), "")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("&nbsp;", " ")
            .trim()
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun indexCacheFile(context: Context): File =
        File(cacheDir(context), "aldos_index.json")

    private fun cheatCacheFile(context: Context, entry: CheatEntry): File =
        File(cacheDir(context), "ncl/${entry.fileName.replace(Regex("[^A-Za-z0-9._ -]"), "_")}.ncl")

    private fun bundledIndex(context: Context): List<CheatEntry>? {
        val text = readAsset(context, BUNDLED_INDEX_ASSET) ?: return null
        return json.decodeFromString(ListSerializer(CheatEntry.serializer()), text)
    }

    private fun bundledCheatText(context: Context, entry: CheatEntry): String? {
        val assetName = entry.assetName
            ?: bundledIndex(context)?.firstOrNull { it.fileName == entry.fileName }?.assetName
            ?: return null
        return readAsset(context, "cheats/ncl/$assetName")
    }

    private fun readAsset(context: Context, path: String): String? {
        return runCatching {
            context.assets.open(path).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    private fun cacheDir(context: Context): File {
        val root = if (RPCSX.rootDirectory.isNotBlank()) {
            File(RPCSX.rootDirectory)
        } else {
            context.getExternalFilesDir(null) ?: context.filesDir
        }

        return File(root, "cheats")
    }
}
