package net.rpcsx.cheats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
