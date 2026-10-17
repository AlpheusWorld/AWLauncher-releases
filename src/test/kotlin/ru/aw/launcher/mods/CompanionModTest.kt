package ru.aw.launcher.mods

import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.toHex
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CompanionModTest {
    @TempDir lateinit var root: Path
    private fun jar(id: String = "awassistant", version: String = "1") = ByteArrayOutputStream().also { buffer ->
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json"))
            zip.write(Json.encodeToString(mapOf("id" to id,"version" to version,"name" to id)).toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()
    private fun target(bytes: ByteArray): Path = Files.createDirectories(root.resolve("mods")).resolve("awassistant.jar").also { Files.write(it,bytes) }
    private fun own(bytes: ByteArray) {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        Files.createDirectories(root.resolve("config"))
        Files.writeString(root.resolve("config/awassistant-managed.json"),Json.encodeToString(mapOf("sha256" to sha)))
    }

    @Test fun ownedModIsDisabledWithoutRemovingUserDataOrOtherMods() {
        val bytes=jar();val active=target(bytes);own(bytes)
        val settings=Files.createDirectories(root.resolve("config/awassistant")).resolve("ui.json")
        Files.writeString(settings,"personal assistant settings")
        val world=Files.createDirectories(root.resolve("saves/My World")).resolve("level.dat");Files.writeString(world,"world data")
        val other=root.resolve("mods/other.jar");Files.writeString(other,"another mod")
        assertTrue(CompanionMod.retire(root))
        assertFalse(Files.exists(active));assertArrayEquals(bytes,Files.readAllBytes(root.resolve("mods/awassistant.jar.disabled")))
        assertEquals("personal assistant settings",Files.readString(settings));assertEquals("world data",Files.readString(world));assertEquals("another mod",Files.readString(other))
        assertFalse(Files.exists(root.resolve("config/awassistant-managed.json")))
        assertFalse(CompanionMod.retire(root))
    }
    @Test fun manualInstallWithoutOwnershipIsPreserved() {
        val bytes=jar();val active=target(bytes)
        assertFalse(CompanionMod.retire(root));assertArrayEquals(bytes,Files.readAllBytes(active))
    }
    @Test fun manuallyReplacedOwnedFileIsPreserved() {
        val original=jar();own(original);val changed=jar(version="custom");val active=target(changed)
        assertFalse(CompanionMod.retire(root));assertArrayEquals(changed,Files.readAllBytes(active))
    }
    @Test fun unrelatedModAtTheOldFilenameIsPreservedEvenWithMatchingRecord() {
        val bytes=jar(id="another_mod");val active=target(bytes);own(bytes)
        assertFalse(CompanionMod.retire(root));assertArrayEquals(bytes,Files.readAllBytes(active))
    }
    @Test fun existingDisabledFilesAreNeverOverwritten() {
        val bytes=jar();target(bytes);own(bytes);val existing=root.resolve("mods/awassistant.jar.disabled");Files.writeString(existing,"old disabled copy")
        assertTrue(CompanionMod.retire(root));assertEquals("old disabled copy",Files.readString(existing))
        val archived=Files.list(root.resolve("mods")).use { stream -> stream.filter { it.fileName.toString().startsWith("awassistant-retired-") }.toList() }
        assertEquals(1,archived.size);assertArrayEquals(bytes,Files.readAllBytes(archived.single()))
    }
    @Test fun brokenOwnershipRecordDoesNotDisableAMod() {
        val bytes=jar();val active=target(bytes);own(bytes);Files.writeString(root.resolve("config/awassistant-managed.json"),"invalid")
        assertFalse(CompanionMod.retire(root));assertArrayEquals(bytes,Files.readAllBytes(active))
    }
    @Test fun retiredFileCanBeExplicitlyReenabledAsAManualMod() {
        val bytes=jar();val active=target(bytes);own(bytes);assertTrue(CompanionMod.retire(root))
        Files.move(root.resolve("mods/awassistant.jar.disabled"),active)
        assertFalse(CompanionMod.retire(root));assertArrayEquals(bytes,Files.readAllBytes(active))
    }
    @Test fun downloadCatalogIsNoLongerPackaged() { assertNull(javaClass.getResource("/companion/catalog.json")) }
}
