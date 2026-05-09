package net.rpcsx.tools

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TrimRisk {
    Safe,
    Risky
}

data class TrimCandidate(
    val displayPath: String,
    val size: Long,
    val reason: String,
    val risk: TrimRisk,
    val isDirectory: Boolean,
    val localFile: File? = null,
    val documentUri: Uri? = null
)

data class TrimApplyResult(
    val backedUp: Int,
    val deleted: Int,
    val backupPath: String?
)

object TrimAnalyzer {
    private val safeFileNames = setOf(
        ".ds_store",
        "thumbs.db",
        "desktop.ini"
    )

    private val riskyMediaExtensions = setOf(
        "ac3",
        "at3",
        "bik",
        "m2v",
        "mp4",
        "pam",
        "sfd",
        "usm"
    )

    private val riskyLanguageTokens = setOf(
        "de",
        "fr",
        "it",
        "es",
        "pt",
        "ru",
        "pl",
        "jp",
        "ja",
        "ko",
        "zh",
        "cn"
    )

    fun analyzeInstalled(path: String): List<TrimCandidate> {
        val root = File(path)
        if (!root.exists()) {
            return emptyList()
        }

        val candidates = mutableListOf<TrimCandidate>()
        root.walkTopDown().forEach { file ->
            if (file == root) {
                return@forEach
            }

            candidateForLocal(root, file)?.let { candidates += it }
        }

        return candidates.sortedWith(compareBy<TrimCandidate> { it.risk }.thenByDescending { it.size })
    }

    fun analyzeExternal(context: Context, uri: Uri): List<TrimCandidate> {
        val root = DocumentFile.fromTreeUri(context, uri) ?: return emptyList()
        val candidates = mutableListOf<TrimCandidate>()

        fun walk(file: DocumentFile, path: String) {
            file.listFiles().forEach { child ->
                val childPath = if (path.isBlank()) child.name.orEmpty() else "$path/${child.name.orEmpty()}"
                candidateForDocument(context, child, childPath)?.let { candidates += it }
                if (child.isDirectory) {
                    walk(child, childPath)
                }
            }
        }

        walk(root, "")
        return candidates.sortedWith(compareBy<TrimCandidate> { it.risk }.thenByDescending { it.size })
    }

    fun apply(context: Context, candidates: List<TrimCandidate>): TrimApplyResult {
        var backupRoot: File? = null
        var backedUp = 0
        var deleted = 0
        candidates.forEach { candidate ->
            val localFile = candidate.localFile
            if (localFile != null && localFile.exists()) {
                val root = backupRoot ?: createBackupRoot(context).also { backupRoot = it }
                val target = backupTarget(root, candidate.displayPath)
                if (moveToBackup(localFile, target)) {
                    backedUp++
                }
                return@forEach
            }

            val documentUri = candidate.documentUri ?: return@forEach
            val document = DocumentFile.fromSingleUri(context, documentUri) ?: return@forEach
            if (document.delete()) {
                deleted++
            }
        }

        return TrimApplyResult(
            backedUp = backedUp,
            deleted = deleted,
            backupPath = backupRoot?.takeIf { backedUp > 0 }?.absolutePath
        )
    }

    fun formatSize(size: Long): String {
        if (size < 1024) return "$size B"
        val units = listOf("KB", "MB", "GB")
        var value = size / 1024.0
        var unit = units.first()
        for (next in units.drop(1)) {
            if (value < 1024.0) break
            value /= 1024.0
            unit = next
        }
        return "%.1f %s".format(value, unit)
    }

    private fun candidateForLocal(root: File, file: File): TrimCandidate? {
        val relativePath = file.relativeTo(root).invariantSeparatorsPath
        val name = file.name.lowercase()

        if (file.isDirectory && name == "ps3_update") {
            return TrimCandidate(
                displayPath = relativePath,
                size = directorySize(file),
                reason = "PS3 firmware update data",
                risk = TrimRisk.Safe,
                isDirectory = true,
                localFile = file
            )
        }

        if (file.isFile && file.length() == 0L) {
            return TrimCandidate(relativePath, 0, "Empty file", TrimRisk.Safe, false, localFile = file)
        }

        if (file.isFile && name in safeFileNames) {
            return TrimCandidate(relativePath, file.length(), "OS metadata file", TrimRisk.Safe, false, localFile = file)
        }

        if (file.isFile && file.extension.lowercase() in riskyMediaExtensions && file.length() > 1_000_000) {
            return TrimCandidate(relativePath, file.length(), "Large media file; preview before deleting", TrimRisk.Risky, false, localFile = file)
        }

        if (file.isDirectory && riskyLanguageTokens.contains(name)) {
            return TrimCandidate(relativePath, directorySize(file), "Language folder; keep languages you use", TrimRisk.Risky, true, localFile = file)
        }

        return null
    }

    private fun candidateForDocument(context: Context, file: DocumentFile, path: String): TrimCandidate? {
        val name = file.name.orEmpty().lowercase()

        if (file.isDirectory && name == "ps3_update") {
            return TrimCandidate(path, documentSize(file), "PS3 firmware update data", TrimRisk.Safe, true, documentUri = file.uri)
        }

        if (file.isFile && file.length() == 0L) {
            return TrimCandidate(path, 0, "Empty file", TrimRisk.Safe, false, documentUri = file.uri)
        }

        if (file.isFile && name in safeFileNames) {
            return TrimCandidate(path, file.length(), "OS metadata file", TrimRisk.Safe, false, documentUri = file.uri)
        }

        val extension = name.substringAfterLast('.', "")
        if (file.isFile && extension in riskyMediaExtensions && file.length() > 1_000_000) {
            return TrimCandidate(path, file.length(), "Large media file; preview before deleting", TrimRisk.Risky, false, documentUri = file.uri)
        }

        if (file.isDirectory && riskyLanguageTokens.contains(name)) {
            return TrimCandidate(path, documentSize(file), "Language folder; keep languages you use", TrimRisk.Risky, true, documentUri = file.uri)
        }

        return null
    }

    private fun directorySize(file: File): Long =
        file.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun documentSize(file: DocumentFile): Long {
        if (file.isFile) {
            return file.length()
        }

        return file.listFiles().sumOf { documentSize(it) }
    }

    private fun createBackupRoot(context: Context): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(baseDir, "trim_backups/$timestamp").apply { mkdirs() }
    }

    private fun backupTarget(root: File, displayPath: String): File {
        val safePath = displayPath
            .split('/', '\\')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString(File.separator)
        return File(root, safePath.ifBlank { "item" })
    }

    private fun moveToBackup(source: File, target: File): Boolean {
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) {
            return true
        }

        return runCatching {
            if (source.isDirectory) {
                source.copyRecursively(target, overwrite = true)
                source.deleteRecursively()
            } else {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
        }.getOrDefault(false)
    }
}
