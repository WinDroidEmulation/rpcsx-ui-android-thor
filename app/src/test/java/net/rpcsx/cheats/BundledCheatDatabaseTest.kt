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
        val failuresFile = File(assetsDir, "download_failures.json")
        val nclDir = File(assetsDir, "ncl")

        val entries = json.decodeFromString(
            ListSerializer(CheatEntry.serializer()),
            indexFile.readText()
        )

        assertEquals("[]", failuresFile.readText().trim())
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
