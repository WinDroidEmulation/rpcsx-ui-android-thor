package net.rpcsx.cheats

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BundledCheatDatabaseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun bundledIndexPointsToBundledNclFiles() {
        val assetsDir = File("src/main/assets/cheats")
        val indexFile = File(assetsDir, "aldos_index.json")
        val dbFile = File(assetsDir, "cheats.db")
        val failuresFile = File(assetsDir, "download_failures.json")
        val nclDir = File(assetsDir, "ncl")

        val entries = json.decodeFromString(
            ListSerializer(CheatEntry.serializer()),
            indexFile.readText()
        )

        assertEquals("[]", failuresFile.readText().trim())
        assertTrue("Missing bundled SQLite cheat DB", dbFile.isFile)
        assertTrue(
            "Bundled cheat DB should be a SQLite database",
            dbFile.inputStream().use { input ->
                String(input.readNBytes(16), Charsets.US_ASCII) == "SQLite format 3\u0000"
            }
        )
        assertEquals(2501, entries.size)
        assertEquals(entries.size, nclDir.listFiles { file -> file.extension == "ncl" }?.size)
        assertTrue(entries.sumOf { it.convertibleCount ?: 0 } > 0)
        assertTrue(entries.sumOf { it.riskyCount ?: 0 } > 0)
        entries.forEach { entry ->
            assertTrue(
                "Missing bundled asset for ${entry.fileName}",
                File(nclDir, entry.assetName ?: "").isFile
            )
        }
    }
}
