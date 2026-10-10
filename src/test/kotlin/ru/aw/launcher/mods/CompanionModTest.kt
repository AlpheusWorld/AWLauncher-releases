package ru.aw.launcher.mods

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.core.sha1Of
import ru.aw.launcher.core.toHex
import ru.aw.launcher.core.writeAtomically
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CompanionModTest {
    @TempDir lateinit var temporary: Path
    private fun jar(version: String) = ByteArrayOutputStream().also { buffer -> ZipOutputStream(buffer).use { zip ->
        zip.putNextEntry(ZipEntry("fabric.mod.json"));zip.write("""{"id":"awassistant","name":"AWAssistant","version":"$version"}""".toByteArray());zip.closeEntry()
    } }.toByteArray()
    private fun artifact(bytes: ByteArray) = CompanionArtifact("26.1.2","test","https://github.com/AlpheusWorld/AWLauncher-releases/releases/download/test/test.jar",
        sha1Of(bytes.inputStream()),MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),bytes.size.toLong())

    @Test fun `an incompatible or unknown loader never receives the companion`() {
        assertFalse(CompanionMod.supportsLoader(null))
        assertFalse(CompanionMod.supportsLoader("0.18.4"))
        assertFalse(CompanionMod.supportsLoader("0.19.3-beta"))
        assertTrue(CompanionMod.supportsLoader("0.19.3"))
        assertTrue(CompanionMod.supportsLoader("0.20.0"))
    }
    @Test fun `fresh installs and owned updates use the verified cache without duplicate mods`() = runBlocking {
        val first=jar("1");var downloads=0
        val download: suspend (ru.aw.launcher.net.DownloadTask) -> Unit = { downloads++;it.dest.writeAtomically(first) }
        CompanionMod.ensureArtifact(artifact(first),temporary,download=download,cacheDir=temporary.resolve("cache"))
        CompanionMod.ensureArtifact(artifact(first),temporary,download=download,cacheDir=temporary.resolve("cache"))
        assertEquals(1,downloads)
        val second=jar("2")
        CompanionMod.ensureArtifact(artifact(second),temporary,download={it.dest.writeAtomically(second)},cacheDir=temporary.resolve("cache"))
        assertEquals("2",ModCompat.read(temporary.resolve("mods/awassistant.jar"))!!.version)
    }
    @Test fun `disabled and manually installed companions are never replaced`() = runBlocking {
        val mods=Files.createDirectories(temporary.resolve("mods"))
        Files.write(mods.resolve("awassistant.jar.disabled"),jar("manual"))
        val download: suspend (ru.aw.launcher.net.DownloadTask) -> Unit = { error("Disabled mods must not be downloaded") }
        CompanionMod.ensureArtifact(artifact(jar("2")),temporary,download=download,cacheDir=temporary.resolve("cache"))
        assertFalse(Files.exists(mods.resolve("awassistant.jar")))
        Files.delete(mods.resolve("awassistant.jar.disabled"));Files.write(mods.resolve("custom-name.jar"),jar("manual"))
        CompanionMod.ensureArtifact(artifact(jar("2")),temporary,download=download,cacheDir=temporary.resolve("cache"))
        assertEquals("manual",ModCompat.read(mods.resolve("custom-name.jar"))!!.version)
    }
    @Test fun `manual installation during a download does not create a duplicate companion`() = runBlocking {
        val bytes=jar("auto")
        CompanionMod.ensureArtifact(artifact(bytes),temporary,download={ task ->
            Files.createDirectories(temporary.resolve("mods"))
            Files.write(temporary.resolve("mods/manual.jar"),jar("manual"))
            task.dest.writeAtomically(bytes)
        },cacheDir=temporary.resolve("cache"))
        assertFalse(Files.exists(temporary.resolve("mods/awassistant.jar")))
        assertEquals("manual",ModCompat.read(temporary.resolve("mods/manual.jar"))!!.version)
    }
    @Test fun `corrupt downloads do not become game mods`() = runBlocking<Unit> {
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            CompanionMod.ensureArtifact(artifact(jar("1")),temporary,download={it.dest.writeAtomically(byteArrayOf(1,2,3))},cacheDir=temporary.resolve("cache"))
        } }
        assertFalse(Files.exists(temporary.resolve("mods/awassistant.jar")))
    }
}
