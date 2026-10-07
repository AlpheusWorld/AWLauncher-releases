package ru.aw.launcher.meta

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LoadersTest {

    @Test
    fun `NeoForge builds map onto the game version, the year-numbered ones included`() {
        assertEquals("1.21.1", LoaderRepository.neoForgeGameVersion("21.1.73"))
        assertEquals("1.21", LoaderRepository.neoForgeGameVersion("21.0.167"))
        assertEquals("1.20.2", LoaderRepository.neoForgeGameVersion("20.2.12-beta"))
        assertEquals("26.3", LoaderRepository.neoForgeGameVersion("26.3.0.16-beta"))
        assertEquals("26.1.2", LoaderRepository.neoForgeGameVersion("26.1.2.109"))
        assertNull(LoaderRepository.neoForgeGameVersion("26.1.0.0-alpha.1+snapshot-1"))
        assertTrue(LoaderKind.NEOFORGE.ownsProfile("neoforge-26.3.0.16-beta", "26.3"))
    }

    @Test
    fun `the newest build comes first whatever order maven lists them in`() {
        val forge = LoaderRepository.forgeBuilds("1.21", listOf("1.21-51.0.9", "1.21-51.0.33", "1.21-51.0.0", "1.20-46.0.1"), null)
        assertEquals(listOf("51.0.33", "51.0.9", "51.0.0"), forge.map { it.version })

        val neoForge = LoaderRepository.neoForgeBuilds(
            "26.3",
            listOf("26.3.0.9-beta", "26.3.0.16-beta", "26.3.0.13-beta", "26.2.0.88", "26.1.0.0-alpha.1+snapshot-1"),
        )
        assertEquals(listOf("26.3.0.16-beta", "26.3.0.13-beta", "26.3.0.9-beta"), neoForge.map { it.version })
        assertFalse(neoForge.first().stable)
        assertTrue(LoaderRepository.neoForgeBuilds("1.21.1", listOf("21.1.251", "21.1.9")).first().stable)
    }

    @Test
    fun `old Forge builds keep the game version suffix their files are named with`() {
        val builds = LoaderRepository.forgeBuilds(
            "1.7.10",
            listOf("1.7.10-10.13.4.1558-1.7.10", "1.7.10-10.13.4.1614-1.7.10"),
            recommended = "10.13.4.1614",
        )
        assertEquals("10.13.4.1614-1.7.10", builds.single { it.recommended }.version)
        assertEquals(
            "https://maven.minecraftforge.net/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/" +
                "forge-1.7.10-10.13.4.1614-1.7.10-installer.jar",
            LoaderRepository.installerUrl(LoaderKind.FORGE, "1.7.10", "10.13.4.1614-1.7.10"),
        )
    }

    @Test
    fun `Forge is offered from 1_7_10 on, and its old profile names are recognised`() {
        assertTrue(LoaderRepository.forgeInstallable("1.7.10"))
        assertTrue(LoaderRepository.forgeInstallable("1.12.2"))
        assertFalse(LoaderRepository.forgeInstallable("1.7.2"))
        assertFalse(LoaderRepository.forgeInstallable("1.6.4"))
        assertFalse(LoaderRepository.forgeInstallable("1.7.10_pre4"))

        assertTrue(LoaderKind.FORGE.ownsProfile("1.7.10-Forge10.13.4.1614-1.7.10", "1.7.10"))
        assertTrue(LoaderKind.FORGE.ownsProfile("1.8.9-forge1.8.9-11.15.1.2318-1.8.9", "1.8.9"))
        assertTrue(LoaderKind.FORGE.ownsProfile("1.20.1-forge-47.4.10", "1.20.1"))
        assertFalse(LoaderKind.FORGE.ownsProfile("1.20.1-forge-47.4.10", "1.20"))
    }
}
