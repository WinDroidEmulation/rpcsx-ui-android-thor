package net.rpcsx.cheats

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rpcsx.Game
import net.rpcsx.RPCSX
import net.rpcsx.utils.GameIdentity
import java.io.File
import java.util.Locale

data class ArtemisWrite(
    val address: String,
    val value: String
)

data class ArtemisAobPatch(
    val searchPattern: String,
    val replacePattern: String
)

data class ArtemisCheat(
    val name: String,
    val author: String,
    val writes: List<ArtemisWrite>,
    val aobPatches: List<ArtemisAobPatch>,
    val unsupportedReasons: List<String>
) {
    val isSupported: Boolean
        get() = writes.isNotEmpty() && unsupportedReasons.isEmpty()
}

data class ArtemisInstallResult(
    val titleId: String?,
    val patchHash: String?,
    val installedCheats: Int,
    val skippedCheats: Int,
    val installedWrites: Int,
    val patchFilePath: String?,
    val configFilePath: String?,
    val backupPaths: List<String>,
    val missingHash: Boolean,
    val message: String
)

object ArtemisConverter {
    private const val PATCH_VERSION = "1.2"
    private const val APP_VERSION = "All"
    private const val PATCH_SECTION_START = "# RPCSX Easy Artemis patches start"
    private const val PATCH_SECTION_END = "# RPCSX Easy Artemis patches end"
    private const val CONFIG_SECTION_START = "# RPCSX Easy Artemis patch config start"
    private const val CONFIG_SECTION_END = "# RPCSX Easy Artemis patch config end"

    private val fixedWriteRegex = Regex("^0\\s+([0-9A-Fa-f]{8})\\s+([0-9A-Fa-f]{8})(?:\\s+.*)?$")
    private val aobReplaceRegex = Regex("^B\\s+([0-9A-Fa-f]{16,})\\s+([0-9A-Fa-f]{16,})(?:\\s+.*)?$", RegexOption.IGNORE_CASE)
    private val whitespaceRegex = Regex("\\s+")

    fun parse(text: String): List<ArtemisCheat> {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split("\n#")
            .mapNotNull { parseBlock(it) }
    }

    suspend fun installEntries(
        context: Context,
        game: Game,
        entries: List<CheatEntry>
    ): ArtemisInstallResult = withContext(Dispatchers.IO) {
        val titleId = GameIdentity.primaryTitleId(game)
            ?: entries.flatMap { it.titleIds }.firstOrNull()

        if (titleId == null) {
            return@withContext ArtemisInstallResult(
                titleId = null,
                patchHash = null,
                installedCheats = 0,
                skippedCheats = 0,
                installedWrites = 0,
                patchFilePath = null,
                configFilePath = null,
                backupPaths = emptyList(),
                missingHash = false,
                message = "No title ID was detected for this game, so RPCSX Easy cannot create a title patch yet."
            )
        }

        val root = rpcsxRoot(context)
        val patchesDir = File(root, "config/patches").apply { mkdirs() }
        val patchFile = File(patchesDir, "${titleId}_patch.yml")
        val configFile = File(root, "config/patch_config.yml")
        val backups = mutableListOf<String>()

        if (entries.isEmpty()) {
            backups += writeGeneratedSection(
                file = patchFile,
                startMarker = PATCH_SECTION_START,
                endMarker = PATCH_SECTION_END,
                body = "",
                prefixIfCreated = "Version: $PATCH_VERSION\n\n"
            )
            backups += writeGeneratedSection(
                file = configFile,
                startMarker = CONFIG_SECTION_START,
                endMarker = CONFIG_SECTION_END,
                body = "",
                prefixIfCreated = ""
            )

            return@withContext ArtemisInstallResult(
                titleId = titleId,
                patchHash = null,
                installedCheats = 0,
                skippedCheats = 0,
                installedWrites = 0,
                patchFilePath = patchFile.absolutePath,
                configFilePath = configFile.absolutePath,
                backupPaths = backups,
                missingHash = false,
                message = "Cleared generated Artemis patch selections for $titleId."
            )
        }

        val ppuHash = PatchHashRepository.requirePpuHash(context, game, titleId)
        if (ppuHash == null) {
            return@withContext ArtemisInstallResult(
                titleId = titleId,
                patchHash = null,
                installedCheats = 0,
                skippedCheats = 0,
                installedWrites = 0,
                patchFilePath = patchFile.absolutePath,
                configFilePath = configFile.absolutePath,
                backupPaths = emptyList(),
                missingHash = true,
                message = "Boot $titleId once, close it, then install again. RPCSX Easy needs the PPU patch hash from RPCSX.log."
            )
        }

        val patchItems = mutableListOf<PatchItem>()
        var skippedCheats = 0

        entries.forEach { entry ->
            val text = CheatRepository.getCheatText(context, entry)
            parse(text).forEach { cheat ->
                if (cheat.isSupported) {
                    patchItems += PatchItem(entry = entry, cheat = cheat)
                } else {
                    skippedCheats++
                }
            }
        }

        val gameTitle = game.info.name.value
            ?: entries.firstOrNull()?.title
            ?: titleId
        val hashKey = "$titleId-$ppuHash"
        val patchBody = buildPatchBody(hashKey, titleId, gameTitle, patchItems)
        val configBody = buildConfigBody(hashKey, titleId, gameTitle, patchItems)

        backups += writeGeneratedSection(
            file = patchFile,
            startMarker = PATCH_SECTION_START,
            endMarker = PATCH_SECTION_END,
            body = patchBody,
            prefixIfCreated = "Version: $PATCH_VERSION\n\n"
        )
        backups += writeGeneratedSection(
            file = configFile,
            startMarker = CONFIG_SECTION_START,
            endMarker = CONFIG_SECTION_END,
            body = configBody,
            prefixIfCreated = ""
        )

        val installedWrites = patchItems.sumOf { it.cheat.writes.size }
        val installedCheats = patchItems.size
        val message = if (installedCheats == 0) {
            "No fixed-write Artemis cheats were installable for $titleId. Risky AoB/configurable codes were skipped."
        } else {
            "Installed $installedCheats Artemis cheats ($installedWrites writes) for next boot. Skipped $skippedCheats risky or unsupported cheats."
        }

        ArtemisInstallResult(
            titleId = titleId,
            patchHash = ppuHash,
            installedCheats = installedCheats,
            skippedCheats = skippedCheats,
            installedWrites = installedWrites,
            patchFilePath = patchFile.absolutePath,
            configFilePath = configFile.absolutePath,
            backupPaths = backups,
            missingHash = false,
            message = message
        )
    }

    fun summarize(text: String): Pair<Int, Int> {
        val cheats = parse(text)
        return cheats.count { it.isSupported } to cheats.count { !it.isSupported }
    }

    private fun parseBlock(rawBlock: String): ArtemisCheat? {
        val lines = rawBlock
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "#" }

        if (lines.isEmpty()) {
            return null
        }

        val name = lines.first().removePrefix("#").trim()
        if (name.isBlank()) {
            return null
        }

        var codeStart = 1
        if (lines.getOrNull(1)?.matches(Regex("[01]")) == true) {
            codeStart = 2
        }

        val author = lines.getOrNull(codeStart)
            ?.takeUnless { looksLikeCodeLine(it) }
            .orEmpty()
            .ifBlank { "Unknown" }
        if (author != "Unknown") {
            codeStart++
        }

        val writes = mutableListOf<ArtemisWrite>()
        val aobPatches = mutableListOf<ArtemisAobPatch>()
        val unsupported = linkedSetOf<String>()

        lines.drop(codeStart).forEach { line ->
            when {
                line.startsWith(";") -> Unit
                line.startsWith("[") -> unsupported += "Configurable placeholder values need a user choice before conversion."
                fixedWriteRegex.matches(line) -> {
                    val match = fixedWriteRegex.matchEntire(line) ?: return@forEach
                    writes += ArtemisWrite(
                        address = match.groupValues[1].uppercase(Locale.US),
                        value = match.groupValues[2].uppercase(Locale.US)
                    )
                }

                aobReplaceRegex.matches(line) -> {
                    val match = aobReplaceRegex.matchEntire(line) ?: return@forEach
                    val searchPattern = match.groupValues[1].uppercase(Locale.US)
                    val replacePattern = match.groupValues[2].uppercase(Locale.US)
                    if (searchPattern.length == replacePattern.length && searchPattern.length % 2 == 0) {
                        aobPatches += ArtemisAobPatch(searchPattern, replacePattern)
                    } else {
                        unsupported += "AoB search/replace pattern lengths do not match."
                    }
                }

                firstToken(line).equals("B", ignoreCase = true) -> {
                    unsupported += "Unsupported AoB search/replace code."
                }

                looksLikeCodeLine(line) -> {
                    unsupported += "Unsupported Artemis code type: ${firstToken(line)}"
                }
            }
        }

        if (aobPatches.isNotEmpty()) {
            unsupported += "AoB search/replace parsed, but needs native byte validation before install."
        }

        if (writes.isEmpty() && unsupported.isEmpty()) {
            return null
        }

        return ArtemisCheat(
            name = name,
            author = author,
            writes = writes,
            aobPatches = aobPatches,
            unsupportedReasons = unsupported.toList()
        )
    }

    private fun looksLikeCodeLine(line: String): Boolean {
        val first = firstToken(line)
        return first.length == 1 && first[0].uppercaseChar() in "0123456789ABCDEF"
    }

    private fun firstToken(line: String): String =
        line.trim().split(whitespaceRegex, limit = 2).firstOrNull().orEmpty()

    private fun buildPatchBody(
        hashKey: String,
        titleId: String,
        gameTitle: String,
        patchItems: List<PatchItem>
    ): String {
        if (patchItems.isEmpty()) {
            return ""
        }

        return buildString {
            appendLine("${yamlKey(hashKey)}:")
            patchItems.forEachIndexed { index, item ->
                val description = item.description(index)
                appendLine("  ${yamlKey(description)}:")
                appendLine("    Games:")
                appendLine("      ${yamlKey(gameTitle)}:")
                appendLine("        ${yamlKey(titleId)}:")
                appendLine("          - $APP_VERSION")
                appendLine("    Author: ${yamlScalar(item.cheat.author)}")
                appendLine("    Patch Version: \"1.0\"")
                appendLine("    Group: \"Artemis\"")
                appendLine("    Notes: ${yamlScalar("Converted from AldosTools Artemis NCL by RPCSX Easy. Source: ${item.entry.fileName}.")}")
                appendLine("    Patch:")
                item.cheat.writes.forEach { write ->
                    appendLine("      - [ be32, 0x${write.address}, 0x${write.value} ]")
                }
            }
        }.trimEnd()
    }

    private fun buildConfigBody(
        hashKey: String,
        titleId: String,
        gameTitle: String,
        patchItems: List<PatchItem>
    ): String {
        if (patchItems.isEmpty()) {
            return ""
        }

        return buildString {
            appendLine("${yamlKey(hashKey)}:")
            patchItems.forEachIndexed { index, item ->
                val description = item.description(index)
                appendLine("  ${yamlKey(description)}:")
                appendLine("    ${yamlKey(gameTitle)}:")
                appendLine("      ${yamlKey(titleId)}:")
                appendLine("        $APP_VERSION:")
                appendLine("          Enabled: true")
            }
        }.trimEnd()
    }

    private fun writeGeneratedSection(
        file: File,
        startMarker: String,
        endMarker: String,
        body: String,
        prefixIfCreated: String
    ): List<String> {
        file.parentFile?.mkdirs()

        val existed = file.exists()
        val original = if (existed) file.readText() else ""
        val backupPaths = mutableListOf<String>()

        if (existed && !original.contains(startMarker)) {
            val backup = File("${file.absolutePath}.rpcsx-easy.bak")
            if (!backup.exists()) {
                file.copyTo(backup, overwrite = false)
                backupPaths += backup.absolutePath
            }
        }

        val section = buildString {
            appendLine(startMarker)
            if (body.isNotBlank()) {
                appendLine(body)
            }
            appendLine(endMarker)
        }

        val current = if (original.isBlank() && prefixIfCreated.isNotBlank()) {
            prefixIfCreated
        } else {
            original
        }

        val updated = if (current.contains(startMarker) && current.contains(endMarker)) {
            val pattern = Regex(
                "${Regex.escape(startMarker)}[\\s\\S]*?${Regex.escape(endMarker)}\\s*"
            )
            pattern.replace(current, section)
        } else {
            buildString {
                append(current.trimEnd())
                if (isNotEmpty()) {
                    appendLine()
                    appendLine()
                }
                append(section)
            }
        }

        file.writeText(updated)
        return backupPaths
    }

    private fun rpcsxRoot(context: Context): File {
        return if (RPCSX.rootDirectory.isNotBlank()) {
            File(RPCSX.rootDirectory)
        } else {
            context.getExternalFilesDir(null) ?: context.filesDir
        }
    }

    private fun yamlKey(value: String): String = yamlScalar(value)

    private fun yamlScalar(value: String): String {
        val clean = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace(whitespaceRegex, " ")
            .trim()
        return "\"$clean\""
    }

    private data class PatchItem(
        val entry: CheatEntry,
        val cheat: ArtemisCheat
    ) {
        fun description(index: Int): String {
            return "Artemis: ${entry.title} - ${cheat.name} [${index + 1}]"
        }
    }
}
