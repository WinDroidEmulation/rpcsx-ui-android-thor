package net.rpcsx.cheats

import org.junit.Assert.assertEquals
import org.junit.Test

class PatchHashRepositoryTest {
    @Test
    fun parseLogTextLearnsTitleScopedHashes() {
        val parsed = PatchHashRepository.parseLogText(
            """
            Booting BLUS12345 from disc path
            PPU executable hash: abcdef1234567890
            SPU executable hash: 1122334455667788
            Booting BLES99999 from disc path
            PPU executable hash: deadbeefdeadbeef
            SPU executable hash: 9988776655443322
            """.trimIndent(),
            "BLUS12345"
        )

        assertEquals("abcdef1234567890", parsed.ppuHashForTitle)
        assertEquals("deadbeefdeadbeef", parsed.ppuHash)
        assertEquals(listOf("1122334455667788"), parsed.spuHashes)
    }
}
