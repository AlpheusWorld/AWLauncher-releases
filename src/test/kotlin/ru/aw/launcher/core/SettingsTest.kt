package ru.aw.launcher.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlin.io.path.readText
import ru.aw.launcher.meta.LoaderKind

class SettingsTest {
    @Test
    fun `existing settings gain Discord preferences without sharing a server address`() {
        val old = Json.decodeFromString<LauncherSettings>("{\"memoryMb\":3584,\"language\":\"RU\"}")
        assertEquals(3584, old.memoryMb)
        assertEquals(true, old.discordPresence)
        assertEquals(true, old.discordShowLauncher)
        assertEquals(true, old.discordShowInstance)
        assertEquals(false, old.discordShowServer)
    }

    @Test
    fun `old settings enable the companion and an explicit opt out survives persistence`() {
        val old = Json.decodeFromString<LauncherSettings>("""{"memoryMb":3584,"language":"RU"}""")
        assertEquals(true, old.companionModAutoDownload)
        val encoded = Json.encodeToString(LauncherSettings.serializer(), old.copy(companionModAutoDownload = false))
        val restored = Json.decodeFromString<LauncherSettings>(encoded)
        assertEquals(false, restored.companionModAutoDownload)
        assertEquals(3584, restored.memoryMb)
    }

    @Test
    fun `recommendations keep room for the OS even on small PCs`() {
        assertEquals(512, SettingsDefaults.recommendedMemory(1024))
        assertEquals(1024, SettingsDefaults.recommendedMemory(2048))
        assertEquals(7168, SettingsDefaults.memoryLimit(8192))
    }

    @Test
    fun `existing settings retain window behavior and can persist the keep open preference`() {
        val old = Json.decodeFromString<LauncherSettings>("""{"memoryMb":3584,"language":"RU"}""")
        assertEquals(false, old.keepLauncherOpen)
        val changed = old.copy(keepLauncherOpen = true)
        val encoded = Json.encodeToString(LauncherSettings.serializer(), changed)
        val restored = Json.decodeFromString<LauncherSettings>(encoded)
        assertEquals(true, restored.keepLauncherOpen)
        assertEquals(3584, restored.memoryMb)
        assertEquals(Language.RU, restored.language)
    }

    @Test
    fun `rapid edits are eventually persisted and explicit save flushes the latest value`() = runBlocking {
        val original = Settings.current
        try {
            repeat(100) { value -> Settings.update { it.copy(jvmArgs = "edit-$value") } }
            assertEquals("edit-99", Settings.current.jvmArgs)
            withTimeout(5_000) {
                while (runCatching {
                    Json.decodeFromString<LauncherSettings>(Paths.settingsFile.readText()).jvmArgs
                }.getOrNull() != "edit-99") delay(25)
            }
            Settings.update { it.copy(jvmArgs = "last-edit") }
            Settings.save()
            assertEquals("last-edit", Json.decodeFromString<LauncherSettings>(Paths.settingsFile.readText()).jvmArgs)
            delay(600)
            assertEquals("last-edit", Json.decodeFromString<LauncherSettings>(Paths.settingsFile.readText()).jvmArgs)
        } finally {
            Settings.update { original }
            Settings.save()
        }
    }

    @Test
    fun `a modded instance is named after game version and loader, not the loader build`() {
        assertEquals("26.3-fabric", Settings.gameDir("26.3", LoaderKind.FABRIC).fileName.toString())
        assertEquals("26.3-neoforge", Settings.gameDir("26.3", LoaderKind.NEOFORGE).fileName.toString())
    }

    @Test
    fun `recommended memory follows the RAM size on the box, not the few MB Windows keeps`() {
        assertEquals(8192, SettingsDefaults.recommendedMemory(32559))
        assertEquals(4096, SettingsDefaults.recommendedMemory(16264))
        assertEquals(2048, SettingsDefaults.recommendedMemory(8032))
        assertEquals(8192, SettingsDefaults.recommendedMemory(65300))
        assertEquals(2048, SettingsDefaults.recommendedMemory(3900))
    }

    @Test
    fun `a vanilla instance keeps the plain version name`() {
        assertEquals("26.3", Settings.gameDir("26.3").fileName.toString())
    }
}
