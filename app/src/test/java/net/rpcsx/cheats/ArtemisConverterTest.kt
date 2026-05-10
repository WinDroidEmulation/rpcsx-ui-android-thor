package net.rpcsx.cheats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArtemisConverterTest {
    @Test
    fun parseFixedWritesAsSupportedCheats() {
        val cheats = ArtemisConverter.parse(
            """
            Infinite Money
            0
            Artemis Team
            0 1030F7B0 05F5E0FF
            0 001FCC10 60000000
            #
            """.trimIndent()
        )

        assertEquals(1, cheats.size)
        assertTrue(cheats.first().isSupported)
        assertEquals(2, cheats.first().writes.size)
        assertEquals("1030F7B0", cheats.first().writes.first().address)
    }

    @Test
    fun parseAobAndPlaceholderCodesAsUnsupported() {
        val text = """
            AoB Patch
            0
            Unknown
            B 0001000000010000 0400000004000000
            #
            Configurable Multiplier
            0
            Unknown
            0 003712D8 3883Z
            [Z]000A=10x;0064=100x[/Z]
            #
            """.trimIndent()
        val cheats = ArtemisConverter.parse(text)

        assertEquals(2, cheats.size)
        assertFalse(cheats[0].isSupported)
        assertFalse(cheats[1].isSupported)
        assertEquals(1, cheats[0].aobPatches.size)
        assertEquals("0001000000010000", cheats[0].aobPatches.first().searchPattern)
        assertEquals(0 to 2, ArtemisConverter.summarize(text))
    }

    @Test
    fun convertOdinSphereLeifthrasirBlusFixture() {
        val source = File(
            "src/main/assets/cheats/ncl/1417_Odin Sphere Leifthrasir BLUS31601 v01.01 av01.00.ncl"
        ).readText()
        val entry = CheatEntry(
            titleIds = listOf("BLUS31601"),
            title = "Odin Sphere Leifthrasir",
            version = "v01.01 av01.00",
            size = "3.21 KB",
            fileName = "Odin Sphere Leifthrasir BLUS31601 v01.01 av01.00",
            assetName = "1417_Odin Sphere Leifthrasir BLUS31601 v01.01 av01.00.ncl"
        )

        val preview = ArtemisConverter.buildPatchPreview(
            entry = entry,
            cheatText = source,
            titleId = "BLUS31601",
            ppuHash = "TEST_PPU_HASH_ODIN_BLUS31601",
            gameTitle = "Odin Sphere Leifthrasir"
        )

        assertEquals(15, preview.installedCheats)
        assertEquals(6, preview.skippedCheats)
        assertEquals(22, preview.installedWrites)
        assertEquals(
            File("src/test/resources/cheats/converted/odin_sphere_leifthrasir_blus31601_patch.yml")
                .readText()
                .trimEnd(),
            preview.patchBody
        )
        assertEquals(
            File("src/test/resources/cheats/converted/odin_sphere_leifthrasir_blus31601_patch_config.yml")
                .readText()
                .trimEnd(),
            preview.configBody
        )
    }

    @Test
    fun mergeReadyRpcS3PatchesUnderSharedHash() {
        val entries = listOf(
            readyEntry(
                "Infinite Health",
                """
                PPU-abc:
                  "Infinite Health":
                    Patch:
                      - [ be32, 0x1000, 0x60000000 ]
                """.trimIndent(),
                """
                PPU-abc:
                  "Infinite Health":
                    "Game":
                      BLUS00000:
                        "01.00":
                          Enabled: true
                """.trimIndent()
            ),
            readyEntry(
                "Infinite Ammo",
                """
                PPU-abc:
                  "Infinite Ammo":
                    Patch:
                      - [ be32, 0x1004, 0x60000000 ]
                """.trimIndent(),
                """
                PPU-abc:
                  "Infinite Ammo":
                    "Game":
                      BLUS00000:
                        "01.00":
                          Enabled: true
                """.trimIndent()
            )
        )

        val preview = ArtemisConverter.buildReadyPatchPreview(entries)

        assertEquals(2, preview.installedCheats)
        assertEquals(2, preview.installedWrites)
        assertEquals(1, Regex("^PPU-abc:", RegexOption.MULTILINE).findAll(preview.patchBody).count())
        assertTrue(preview.patchBody.contains("\"Infinite Health\""))
        assertTrue(preview.patchBody.contains("\"Infinite Ammo\""))
        assertEquals(1, Regex("^PPU-abc:", RegexOption.MULTILINE).findAll(preview.configBody).count())
    }

    private fun readyEntry(name: String, patchBody: String, configBody: String): CheatEntry =
        CheatEntry(
            titleIds = listOf("BLUS00000"),
            title = name,
            version = "01.00",
            size = "1 patch op",
            fileName = name,
            sourceName = "Test",
            format = CheatRepository.FORMAT_RPCS3_PATCH,
            patchHash = "PPU-abc",
            readyPatchBody = patchBody,
            readyConfigBody = configBody
        )
}
