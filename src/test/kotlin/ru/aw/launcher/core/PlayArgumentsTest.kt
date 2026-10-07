package ru.aw.launcher.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.aw.launcher.meta.LoaderKind

class PlayArgumentsTest {

    @Test
    fun `desktop shortcut keeps the selected named build`() {
        val buildId = "72a4ca77-3aae-4c19-b237-652bf6a4fd12"
        val arguments = PlayArguments.of("26.3", LoaderKind.FABRIC, build = buildId)

        assertTrue("--build \"$buildId\"" in arguments)
        assertEquals(
            PlayRequest("26.3", LoaderKind.FABRIC, build = buildId),
            PlayArguments.parse(arrayOf("--play", "26.3", "--loader", "fabric", "--build", buildId)),
        )
    }
}
